package com.biomata.mc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final String BRAIN_CLASS = "src.plugins.builtin.ollama.brain.OllamaLLMBrain";
    private static final String MODEL       = "qwen2.5:14b";
    private static final Gson GSON = new Gson();

    private volatile WebSocket ws;
    /** True while a tick is awaiting the engine — paces ticks to LLM latency. */
    private volatile boolean tickInFlight;

    /** requestId -> method, so each reply is dispatched by what it answered. */
    private final Map<String, String> pending = new ConcurrentHashMap<>();
    private final Set<String> registeredAgents = ConcurrentHashMap.newKeySet();

    /** agentId -> {targetX, targetZ}. Written on the WS thread, read on server thread. */
    public final Map<String, double[]> moveTargets = new ConcurrentHashMap<>();

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
        tickInFlight = false;
    }

    public boolean isReady() {
        return ws != null && !registeredAgents.isEmpty();
    }

    private static final String DEFAULT_PROMPT =
        "You are a curious character exploring a Minecraft world. You love to roam. "
        + "Strongly prefer the 'move' action, and when you move include target_x "
        + "and target_z coordinates a short distance from your current position.";

    /** Register one agent driven by a local Ollama LLM. `prompt` null → default. */
    public void registerAgent(String agentId, String prompt) {
        WebSocket s = ws;
        if (s == null) return;
        s.sendText(registerFrame(agentId, prompt == null ? DEFAULT_PROMPT : prompt), true);
    }

    /**
     * Change an agent's system prompt. The engine sets brain config at register
     * time, so this re-registers: remove_agent then register_agent, chained so
     * the two sends don't overlap and the engine sees the remove first.
     */
    public void setPrompt(String agentId, String prompt) {
        WebSocket s = ws;
        if (s == null) return;
        JsonObject rm = new JsonObject();
        rm.addProperty("agent_id", agentId);
        String removeFrame = request("remove_agent", rm);
        String registerFrame = registerFrame(agentId, prompt);
        s.sendText(removeFrame, true).thenCompose(v -> s.sendText(registerFrame, true));
    }

    /** Build a register_agent frame for one Ollama-driven agent. */
    private String registerFrame(String agentId, String prompt) {
        JsonObject llm = new JsonObject();
        llm.addProperty("model", MODEL);
        llm.addProperty("base_url", "http://localhost:11434");
        llm.addProperty("temperature", 0.8);
        llm.addProperty("max_concurrent", 2);

        JsonArray traits = new JsonArray();
        traits.add("curious");
        traits.add("restless");
        JsonArray goals = new JsonArray();
        goals.add("explore new places");
        JsonObject personality = new JsonObject();
        personality.add("traits", traits);
        personality.add("goals", goals);
        personality.addProperty("backstory", "A wanderer with wanderlust in a blocky world.");

        JsonObject brainConfig = new JsonObject();
        brainConfig.add("llm_config", llm);
        brainConfig.add("personality", personality);
        brainConfig.addProperty("system_prompt", prompt);

        JsonObject params = new JsonObject();
        params.addProperty("agent_id", agentId);
        params.addProperty("agent_name", agentId);
        params.addProperty("brain_class", BRAIN_CLASS);
        params.add("brain_config", brainConfig);
        params.add("capabilities", new JsonArray());
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

    /** Advance the engine one tick with every agent's position. */
    public void sendTick(Map<String, double[]> positions) {
        WebSocket s = ws;
        if (s == null || positions.isEmpty()) return;

        tickInFlight = true;
        JsonArray obsList = new JsonArray();
        for (var e : positions.entrySet()) {
            double[] p = e.getValue();
            JsonObject pos = new JsonObject();
            pos.addProperty("x", p[0]);
            pos.addProperty("y", p[1]);
            pos.addProperty("z", p[2]);
            JsonObject obs = new JsonObject();
            obs.add("position", pos);
            JsonObject entry = new JsonObject();
            entry.addProperty("agent_id", e.getKey());
            entry.add("observation", obs);
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
                    if (!"move".equals(action)) continue;
                    if (!d.has("parameters") || d.get("parameters").isJsonNull()) continue;
                    JsonObject p = d.getAsJsonObject("parameters");
                    if (p.has("target_x") && p.has("target_z")) {
                        moveTargets.put(agentId, new double[] {
                            p.get("target_x").getAsDouble(),
                            p.get("target_z").getAsDouble(),
                        });
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
}
