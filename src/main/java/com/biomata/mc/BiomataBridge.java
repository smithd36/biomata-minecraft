package com.biomata.mc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Phase 3 — the bridge, now multi-agent. Registers N agents (IdleBrain emitting
 * `move`), ticks the whole sim once per game second with every agent's position,
 * and publishes each agent's move target for the server thread to apply.
 *
 * Replies are correlated to requests by their `id` (pending map), so a
 * register / tick / remove response is never mistaken for another.
 */
public final class BiomataBridge {
    private static final String ENGINE_URI  = "ws://localhost:8765";
    private static final String BRAIN_CLASS   = "src.plugins.builtin.ollama.brain.OllamaLLMBrain";
    private static final String DEFAULT_MODEL = "qwen2.5:14b";
    private static final Gson GSON = new Gson();

    private volatile WebSocket ws;
    /** True while a tick is awaiting the engine — paces ticks to LLM latency. */
    private volatile boolean tickInFlight;

    /** requestId -> method, so each reply is dispatched by what it answered. */
    private final Map<String, String> pending = new ConcurrentHashMap<>();
    private final Set<String> registeredAgents = ConcurrentHashMap.newKeySet();

    /** agentId -> {targetX, targetZ}. Written on the WS thread, read on server thread. */
    public final Map<String, double[]> moveTargets = new ConcurrentHashMap<>();

    /** `{agentId, text}` speak events. Written on the WS thread, drained on the server thread. */
    public final Queue<String[]> speech = new ConcurrentLinkedQueue<>();

    /** `{agentId, interaction, target}` interact events. Written on the WS thread,
     *  dispatched (capability-gated) on the server thread. */
    public final Queue<String[]> interactions = new ConcurrentLinkedQueue<>();

    public void connect() {
        BiomataMinecraft.LOGGER.info("[biomata] connecting to {}", ENGINE_URI);
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create(ENGINE_URI), new Listener())
            .whenComplete((socket, err) -> {
                if (err != null) {
                    BiomataMinecraft.LOGGER.error(
                        "[biomata] connect failed — is `biomata-ws` running on 8765?", err);
                    return;
                }
                this.ws = socket;
            });
    }

    public void disconnect() {
        WebSocket s = ws;
        ws = null;
        if (s != null) {
            // Remove every registered agent (sequentially — sends must not
            // overlap), then close.
            CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
            for (String id : registeredAgents) {
                JsonObject params = new JsonObject();
                params.addProperty("agent_id", id);
                String frame = request("remove_agent", params);
                chain = chain.thenCompose(v -> s.sendText(frame, true));
            }
            chain.thenRun(() -> s.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping"));
        }
        pending.clear();
        registeredAgents.clear();
        moveTargets.clear();
        speech.clear();
        interactions.clear();
        tickInFlight = false;
    }

    public boolean isReady() {
        return ws != null && !registeredAgents.isEmpty();
    }

    /** True once the WebSocket is open (whether or not any agent is registered yet). */
    public boolean isConnected() {
        return ws != null;
    }

    /**
     * Uniform game mechanics, sent as every agent's {@code system_prompt}: how to
     * read observations and which actions to use. Deliberately NOT character —
     * who an agent *is* lives in its per-agent persona (backstory), below.
     */
    private static final String SYSTEM_INSTRUCTIONS =
        "You are a character living in a Minecraft world. Your observation describes "
        + "your surroundings: 'nearby_agents' (other characters, with id + position), "
        + "'nearby_hostiles' (dangerous mobs, with type + distance), 'hazards' "
        + "(e.g. in_water, on_fire, in_lava), 'time_of_day', 'light_level', and your "
        + "'health'. Use 'move' (with target_x and target_z) to wander, approach "
        + "someone, or flee danger — move away from hostiles and out of hazards. When "
        + "another character is close and it feels right, use 'speak' (with a 'text' "
        + "field) to say something. If you have messages under 'MESSAGES RECEIVED', "
        + "someone spoke to you — reply to what they actually said instead of "
        + "greeting again. Use 'interact' for the special interactions listed in your "
        + "character — some (like resting) act on yourself and need no target, so just "
        + "set parameters.interaction. If 'asleep' is true you are resting; choosing "
        + "'move' at any time gets you up. Decide what to do from what you perceive and "
        + "who you are — e.g. your surroundings, the time of day, and your own state.";

    /** Character for an agent with no authored persona — a blank-slate wanderer. */
    private static final String DEFAULT_PERSONA =
        "A curious, restless wanderer who loves to roam and meet others, but values "
        + "their own safety.";

    /**
     * Register one agent driven by a local Ollama LLM. `persona` null → default;
     * `capabilities` are the agent's role capability tags (may be empty).
     */
    public void registerAgent(String agentId, String persona, List<String> goals,
                              List<String> capabilities, String model) {
        WebSocket s = ws;
        if (s == null) return;
        s.sendText(registerFrame(agentId, persona, goals, capabilities, model), true);
    }

    /**
     * Re-register: remove_agent *then* register_agent, chained. Used both to
     * change an agent's persona/capabilities live and on the reconnect/respawn
     * path — on restart the engine may still hold host-owned agents from the
     * dropped connection, so a plain register would fail AGENT_EXISTS; the leading
     * remove clears any stale registration first (a NOT_FOUND warn if there was
     * none is harmless). Chained so the two sends don't overlap.
     */
    public void reRegisterAgent(String agentId, String persona, List<String> goals,
                                List<String> capabilities, String model) {
        WebSocket s = ws;
        if (s == null) return;
        JsonObject rm = new JsonObject();
        rm.addProperty("agent_id", agentId);
        String removeFrame = request("remove_agent", rm);
        String reg = registerFrame(agentId, persona, goals, capabilities, model);
        s.sendText(removeFrame, true).thenCompose(v -> s.sendText(reg, true));
    }

    /** Build a register_agent frame for one Ollama-driven agent. `model` null → default. */
    private String registerFrame(String agentId, String persona, List<String> goals,
                                 List<String> capabilities, String model) {
        JsonObject llm = new JsonObject();
        llm.addProperty("model", model == null || model.isEmpty() ? DEFAULT_MODEL : model);
        llm.addProperty("base_url", "http://localhost:11434");
        llm.addProperty("temperature", 0.8);
        llm.addProperty("max_concurrent", 2);

        // Persona (backstory) and goals are the differentiation levers: both render
        // into the agent's per-tick "YOUR CHARACTER" block, distinct from the uniform
        // mechanics in system_prompt. Goals give the agent a *reason* to act; without
        // them behaviour is purely reactive. Empty goals → engine default.
        // ponytail: traits still fall to engine defaults — add if a command needs them.
        JsonObject personality = new JsonObject();
        personality.addProperty("backstory", persona == null ? DEFAULT_PERSONA : persona);
        if (goals != null && !goals.isEmpty()) {
            JsonArray g = new JsonArray();
            goals.forEach(g::add);
            personality.add("goals", g);
        }

        JsonObject brainConfig = new JsonObject();
        brainConfig.add("llm_config", llm);
        brainConfig.add("personality", personality);
        brainConfig.addProperty("system_prompt", SYSTEM_INSTRUCTIONS);

        // Capability tags gate engine-side action/observation schemas by role.
        // Host-defined actions ride the generic `interact` verb (dispatched
        // host-side); these tags are the wire-correct record of the agent's role.
        JsonArray caps = new JsonArray();
        if (capabilities != null) capabilities.forEach(caps::add);

        JsonObject params = new JsonObject();
        params.addProperty("agent_id", agentId);
        params.addProperty("agent_name", agentId);
        params.addProperty("brain_class", BRAIN_CLASS);
        params.add("brain_config", brainConfig);
        params.add("capabilities", caps);
        return request("register_agent", params);
    }

    /** Unregister one agent from the engine and forget its state. */
    public void unregisterAgent(String agentId) {
        registeredAgents.remove(agentId);
        moveTargets.remove(agentId);
        WebSocket s = ws;
        if (s != null) {
            JsonObject params = new JsonObject();
            params.addProperty("agent_id", agentId);
            s.sendText(request("remove_agent", params), true);
        }
    }

    /** True if a tick is already awaiting the engine (skip sending another). */
    public boolean isTickInFlight() {
        return tickInFlight;
    }

    /**
     * Advance the engine one tick. Observations are assembled by the caller (on
     * the server thread, where the world lives); the bridge only serializes and
     * transports them. Each value is one agent's observation dict — the engine
     * consumes it as-is (HostedWorld), so its shape is the host's contract.
     */
    public void sendTick(Map<String, Map<String, Object>> observations) {
        WebSocket s = ws;
        if (s == null || observations.isEmpty()) return;

        tickInFlight = true;
        JsonArray obsList = new JsonArray();
        for (var e : observations.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("agent_id", e.getKey());
            entry.add("observation", GSON.toJsonTree(e.getValue()));
            obsList.add(entry);
        }
        JsonObject params = new JsonObject();
        params.add("agent_observations", obsList);
        s.sendText(request("tick", params), true);
    }

    /** Build a request, recording its id -> method so the reply can be routed. */
    private String request(String method, JsonObject params) {
        String id = UUID.randomUUID().toString();
        pending.put(id, method);
        JsonObject req = new JsonObject();
        req.addProperty("type", "req");
        req.addProperty("v", 1);
        req.addProperty("id", id);
        req.addProperty("method", method);
        req.add("params", params);
        return GSON.toJson(req);
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String raw = buf.toString();
                buf.setLength(0);
                onFrame(JsonParser.parseString(raw).getAsJsonObject());
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        private void onFrame(JsonObject frame) {
            if (!"res".equals(optString(frame, "type"))) return;
            String method = pending.remove(optString(frame, "id"));

            if (frame.has("ok") && !frame.get("ok").getAsBoolean()) {
                BiomataMinecraft.LOGGER.warn("[biomata] engine error ({}): {}", method, frame);
                return;
            }
            if (!frame.has("result") || frame.get("result").isJsonNull()) return;
            JsonObject result = frame.getAsJsonObject("result");

            if ("register_agent".equals(method)) {
                String id = optString(result, "agent_id");
                registeredAgents.add(id);
                BiomataMinecraft.LOGGER.info("[biomata] agent registered: {} ({} total)",
                    id, registeredAgents.size());
            } else if ("tick".equals(method)) {
                tickInFlight = false;
                if (!result.has("decisions")) return;
                for (var el : result.getAsJsonArray("decisions")) {
                    JsonObject d = el.getAsJsonObject();
                    String agentId = optString(d, "agent_id");
                    String action = optString(d, "action");
                    BiomataMinecraft.LOGGER.info("[biomata] {} -> {} {}",
                        agentId, action, d.has("parameters") ? d.get("parameters") : "");
                    JsonObject p = (d.has("parameters") && !d.get("parameters").isJsonNull())
                        ? d.getAsJsonObject("parameters") : null;
                    switch (action) {
                        case "move" -> {
                            if (p != null && p.has("target_x") && p.has("target_z")) {
                                moveTargets.put(agentId, new double[] {
                                    p.get("target_x").getAsDouble(),
                                    p.get("target_z").getAsDouble(),
                                });
                            }
                        }
                        case "speak" -> {
                            if (p == null) break;
                            String text = optString(p, "text");
                            if (text.isEmpty()) text = optString(p, "message");
                            if (!text.isEmpty()) speech.add(new String[] {agentId, text});
                        }
                        case "interact" -> {
                            // `interaction` + `target` are resolved by the engine's
                            // interact handler into the engine_command — target rides
                            // intent.target (top-level), which is NOT echoed in
                            // `parameters`, so read the command, not the params.
                            String[] ix = interactCommand(d);
                            if (ix != null) interactions.add(new String[] {agentId, ix[0], ix[1]});
                        }
                        default -> { /* idle — nothing to apply */ }
                    }
                }
            }
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            BiomataMinecraft.LOGGER.error("[biomata] socket error", error);
        }
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    /**
     * Pull `{interaction, target}` from a decision's `engine_commands` (the engine
     * resolves intent.target + the interaction param into the interact command).
     * Returns null if there's no usable interact command — a bare `interact` with
     * no named interaction (engine defaults it to the literal "interact") is
     * ignored here and, being ungranted, would be gated out host-side anyway.
     */
    private static String[] interactCommand(JsonObject d) {
        if (!d.has("engine_commands") || !d.get("engine_commands").isJsonArray()) return null;
        for (var ec : d.getAsJsonArray("engine_commands")) {
            if (!ec.isJsonObject()) continue;
            JsonObject cmd = ec.getAsJsonObject();
            if (!"interact".equals(optString(cmd, "type"))) continue;
            String interaction = optString(cmd, "interaction");
            if (interaction.isEmpty() || interaction.equals("interact")) return null;
            return new String[] {interaction, optString(cmd, "target")};
        }
        return null;
    }
}
