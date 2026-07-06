package com.biomata.mc;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.LevelResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BiomataMinecraft implements ModInitializer {
	public static final String MOD_ID = "biomata-minecraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final BiomataBridge BRIDGE = new BiomataBridge();

	/**
	 * How an agent's in-world body is spawned. This is the extension seam: the
	 * mod is non-opinionated about what an agent *is*. The default embodies
	 * agents as villagers, but a downstream mod can reassign this in its
	 * initializer to spawn any entity — a custom NPC, a configured mob, whatever
	 * — without touching the rest of the pipeline. Per-agent type selection
	 * (via prompt / command) will layer on top of this later.
	 */
	@FunctionalInterface
	public interface CharacterSpawner {
		Mob spawn(ServerLevel level, BlockPos pos);
	}

	public static CharacterSpawner characterSpawner =
		(level, pos) -> EntityTypes.VILLAGER.spawn(level, pos, EntitySpawnReason.COMMAND);

	// ── Roles, capabilities, interactions (host-defined) ──────────────────────
	// The engine ships only 4 verbs (idle/move/speak/interact), none gated, so the
	// role vocabulary lives here. A *role* bundles capabilities + a default persona
	// + a body; a *capability* grants a set of *interactions* — host actions that
	// ride the generic `interact` verb (the engine passes it straight through, we
	// dispatch it). All three maps are public seams: a downstream mod (or, later, an
	// authoring command/config) populates them; the built-ins below are examples.
	// ponytail: code-defined for now — runtime authoring layers on top when needed.

	/**
	 * A host action an agent performs via `interact`, dispatched on the server
	 * thread. {@code target} is another agent for a targeted action, or null for a
	 * self-directed one (see {@link Action#targeted}). {@code selfId} is handed in
	 * so handlers can touch host state keyed by agent id (move targets, inboxes, …).
	 */
	@FunctionalInterface
	public interface Interaction {
		void apply(ServerLevel level, String selfId, Mob self, Mob target);
	}

	/**
	 * A registered action: its LLM-facing description, whether it needs a target
	 * agent ({@code targeted}) or acts on the self ({@code targeted=false}),
	 * {@code aliases} the LLM might emit instead of the canonical name (models pick
	 * synonyms — and the engine's own interact schema pushes words like "use"), and
	 * the handler. New verbs are just new entries — the dispatch/persona pipeline is
	 * generic over this shape.
	 */
	public record Action(String description, boolean targeted, List<String> aliases, Interaction handler) {}

	/** A spawnable role: how it's embodied (null spawner = default), its default
	 *  persona, and its capability tags. */
	public record Role(CharacterSpawner spawner, String persona, List<String> capabilities) {}

	/** interaction name -> handler + description. */
	public static final Map<String, Action> INTERACTIONS = new LinkedHashMap<>();
	/** capability tag -> the interaction names it grants. */
	public static final Map<String, List<String>> CAPABILITIES = new LinkedHashMap<>();
	/** role name -> definition. */
	public static final Map<String, Role> ROLES = new LinkedHashMap<>();

	static {
		// Targeted example: a companion walks over to another agent.
		// ponytail: one-shot navigate — once the path completes, the normal
		// move-target walk loop resumes toward the last engine target. Enough to
		// demonstrate the verb; make it sticky (re-issue while target moves) if
		// follow needs to actually track.
		INTERACTIONS.put("follow", new Action(
			"walk over to and stay near the target agent",
			true, List.of("accompany", "escort"),
			(level, selfId, self, target) -> self.getNavigation().moveTo(
				target.getX(), target.getY(), target.getZ(), 1.3)));

		// Self-directed example: lie down in a bed you've walked next to, using MC's
		// own sleep mechanic. The agent decides *when* (persona/observations), not a
		// vanilla schedule; it stays asleep until it decides to move (which wakes it).
		INTERACTIONS.put("sleep", new Action(
			"lie down and sleep in a bed. Only works when you are right next to a bed, "
				+ "so 'move' to a bed you see in 'nearby_objects' first. Invoke with "
				+ "parameters {\"interaction\":\"sleep\"} and no target. You remain asleep "
				+ "until you 'move' again",
			false, List.of("rest", "nap", "lie_down", "lie down", "use", "bed"),
			(level, selfId, self, target) -> sleepInNearbyBed(level, selfId, self)));

		CAPABILITIES.put("social", List.of());                  // talk only, no extra verbs
		CAPABILITIES.put("companionship", List.of("follow"));
		CAPABILITIES.put("rest", List.of("sleep"));

		ROLES.put("villager", new Role(null,
			"A friendly villager who enjoys chatting with neighbors and sleeps at night.",
			List.of("social", "rest")));
		ROLES.put("companion", new Role(null,
			"A loyal companion who likes to stay close to those they trust.",
			List.of("social", "companionship", "rest")));
	}

	/** agentId -> the entity it drives. Server-thread only. */
	private static final Map<String, Mob> AGENTS = new LinkedHashMap<>();

	/** agentId -> role name (absent = default/roleless). Server-thread only. */
	private static final Map<String, String> AGENT_ROLE = new LinkedHashMap<>();

	/** agentId -> Ollama model override (absent = engine/bridge default). Server-thread only. */
	private static final Map<String, String> AGENT_MODEL = new LinkedHashMap<>();

	/** agentId -> raw goal text (`;`-separated for multiple; absent = engine default). Server-thread only. */
	private static final Map<String, String> AGENT_GOAL = new LinkedHashMap<>();

	/** agentId -> authored persona / character (absent = default). Server-thread only. */
	private static final Map<String, String> PROMPTS = new LinkedHashMap<>();

	/** Agents loaded from disk, awaiting respawn once the bridge connects. Server-thread only. */
	private static final Map<String, SavedAgent> PENDING_LOAD = new LinkedHashMap<>();

	/** agentId -> speech it has heard but not yet observed (`{fromId, text}`). Drained
	 *  into the next observation as `incoming_messages`, which is how the engine
	 *  brain lets an agent answer. Server-thread only. */
	private static final Map<String, List<String[]>> INBOX = new LinkedHashMap<>();

	private static final Gson GSON = new Gson();
	private static final String ROSTER_FILE = "biomata_agents.json";

	/** Scoreboard tag stamped on every agent body, so orphans saved by the world
	 *  (agent entities persist in chunk data) can be swept on respawn. */
	private static final String AGENT_TAG = "biomata_agent";

	private static int serverTicks;

	@Override
	public void onInitialize() {
		LOGGER.info("Hello Fabric world!");

		// Connect to biomata-engine when a world/server starts, then queue any
		// previously-saved agents for respawn (once the bridge is connected).
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			BRIDGE.connect();
			loadRoster(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			saveRoster(server);
			BRIDGE.disconnect();
			AGENTS.clear();
			AGENT_ROLE.clear();
			AGENT_MODEL.clear();
			AGENT_GOAL.clear();
			PROMPTS.clear();
			PENDING_LOAD.clear();
			INBOX.clear();
		});

		// Authoring UI: `/biomata spawn|remove|list`. Agents are created by the
		// player at runtime, not hardcoded.
		CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
			dispatcher.register(Commands.literal("biomata")
				.then(Commands.literal("spawn")
					.then(Commands.argument("id", StringArgumentType.word())
						.executes(BiomataMinecraft::cmdSpawn)
						.then(Commands.argument("role", StringArgumentType.word())
							.executes(BiomataMinecraft::cmdSpawnRole)
							// greedyString, not word(): model names contain ':' (e.g.
							// gemma2:27b), which word() rejects. Safe as the last arg.
							.then(Commands.argument("model", StringArgumentType.greedyString())
								.executes(BiomataMinecraft::cmdSpawnRoleModel)))))
				.then(Commands.literal("prompt")
					.then(Commands.argument("id", StringArgumentType.word())
						.then(Commands.argument("text", StringArgumentType.greedyString())
							.executes(BiomataMinecraft::cmdPrompt))))
				.then(Commands.literal("goal")
					.then(Commands.argument("id", StringArgumentType.word())
						.then(Commands.argument("text", StringArgumentType.greedyString())
							.executes(BiomataMinecraft::cmdGoal))))
				.then(Commands.literal("remove")
					.then(Commands.argument("id", StringArgumentType.word())
						.executes(BiomataMinecraft::cmdRemove)))
				.then(Commands.literal("list")
					.executes(BiomataMinecraft::cmdList))
				.then(Commands.literal("roles")
					.executes(BiomataMinecraft::cmdRoles))));

		// Each server tick, walk every agent toward its engine target; drive the
		// engine ~every 5s (never while a tick is still awaiting the LLM).
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			// Render `speak` decisions to chat, and route them to nearby agents so
			// they can answer — the engine reads `incoming_messages` from the next
			// observation we build. Without this, agents greet but never converse.
			String[] said;
			while ((said = BRIDGE.speech.poll()) != null) {
				String from = said[0], text = said[1];
				server.getPlayerList().broadcastSystemMessage(
					Component.literal("<" + from + "> " + text), false);
				Mob speaker = AGENTS.get(from);
				if (speaker == null || speaker.isRemoved()) continue;
				for (var e : AGENTS.entrySet()) {
					if (e.getKey().equals(from) || e.getValue().isRemoved()) continue;
					// Heard if within the same radius they perceive each other at.
					if (speaker.distanceTo(e.getValue()) <= NEARBY_AGENT_RADIUS) {
						INBOX.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
							.add(new String[] {from, text});
					}
				}
			}

			// Apply `interact` decisions — dispatched to the host action bound to the
			// interaction name, but only if the agent's role actually grants it.
			String[] act;
			while ((act = BRIDGE.interactions.poll()) != null) {
				dispatchInteraction(act[0], act[1], act[2]);
			}

			// Respawn saved agents once the engine connection is up (needs both
			// the world [this thread] and the socket [set on the WS thread]).
			if (!PENDING_LOAD.isEmpty() && BRIDGE.isConnected()) {
				respawnSaved(server);
			}

			if (AGENTS.isEmpty()) return;

			for (var e : AGENTS.entrySet()) {
				Mob p = e.getValue();
				if (p.isRemoved()) continue;
				double[] t = BRIDGE.moveTargets.get(e.getKey());
				if (t == null) continue;
					// A durative self-action (e.g. sleep) clears its move target, so a
					// target present again means the engine issued a *fresh* move — wake
					// a sleeper, then walk. Generic "resume movement ends the current
					// activity" rule, not a sleep special-case.
					if (p.isSleeping()) p.stopSleeping();
					if (p.getNavigation().isDone()) {
					double dx = t[0] - p.getX();
					double dz = t[1] - p.getZ();
					double len = Math.sqrt(dx * dx + dz * dz);
					if (len > 0.5) {
						double step = Math.min(8.0, len);
						p.getNavigation().moveTo(
							p.getX() + dx / len * step,
							p.getY(),
							p.getZ() + dz / len * step, 1.2);
					}
				}
			}

			if (++serverTicks % 100 == 0 && BRIDGE.isReady() && !BRIDGE.isTickInFlight()) {
				// Snapshot positions first (nearby_agents needs everyone), then
				// assemble each agent's full observation on this (server) thread.
				Map<String, double[]> positions = new LinkedHashMap<>();
				for (var e : AGENTS.entrySet()) {
					Mob p = e.getValue();
					if (!p.isRemoved()) {
						positions.put(e.getKey(), new double[] {p.getX(), p.getY(), p.getZ()});
					}
				}
				Map<String, Map<String, Object>> observations = new LinkedHashMap<>();
				for (var e : AGENTS.entrySet()) {
					Mob p = e.getValue();
					if (!p.isRemoved()) {
						observations.put(e.getKey(), buildObservation(e.getKey(), p, positions));
					}
				}
				BRIDGE.sendTick(observations);
			}
		});
	}

	// ── Observation assembly ─────────────────────────────────────────────────
	// Runs on the server thread (has entities + world). Each slice below is a
	// small, independent contributor — the extensible "observation provider"
	// shape. Add awareness by adding a slice; a modder can do the same later.

	private static final double NEARBY_AGENT_RADIUS = 32.0;
	private static final double HOSTILE_RADIUS      = 16.0;
	private static final int    OBJECT_SIGHT        = 10;   // notable blocks within this are perceived
	private static final int    BED_REACH           = 3;    // must be this close to lie down

	private static Map<String, Object> buildObservation(String id, Mob mob, Map<String, double[]> positions) {
		Level level = mob.level();
		Map<String, Object> obs = new LinkedHashMap<>();
		obs.put("position", pos(mob.getX(), mob.getY(), mob.getZ()));
		obs.put("nearby_agents", nearbyAgents(id, positions));
		obs.put("nearby_hostiles", nearbyHostiles(mob, level));
		obs.put("hazards", hazards(mob));
		obs.put("time_of_day", timeOfDay(level));
		obs.put("light_level", level.getMaxLocalRawBrightness(mob.blockPosition()));
		obs.put("health", round1(mob.getHealth()));
		// Perception is facts, not decisions: report the agent's own state and the
		// notable things around it, and let the LLM connect them to actions (bed
		// nearby + night → sleep; asleep + day → move to get up). No pre-computed
		// "at_bed"/"time_to_wake" affordances — those are cognition, the LLM's job.
		if (mob.isSleeping()) obs.put("asleep", true);
		List<Map<String, Object>> objects = nearbyObjects(level, mob.blockPosition());
		if (!objects.isEmpty()) obs.put("nearby_objects", objects);
		List<Map<String, Object>> heard = incomingMessages(id);
		if (!heard.isEmpty()) obs.put("incoming_messages", heard);
		return obs;
	}

	/**
	 * General perception of notable blocks nearby — not tied to any one action.
	 * Reports each as {type, x, y, z, distance}; the LLM decides what to do with
	 * a bed / door / chest it sees. Extend {@link #notableType} to perceive more.
	 */
	private static List<Map<String, Object>> nearbyObjects(Level level, BlockPos c) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (BlockPos p : BlockPos.betweenClosed(
				c.offset(-OBJECT_SIGHT, -4, -OBJECT_SIGHT), c.offset(OBJECT_SIGHT, 4, OBJECT_SIGHT))) {
			String type = notableType(level.getBlockState(p));
			if (type == null) continue;
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("type", type);
			m.put("x", p.getX());
			m.put("y", p.getY());
			m.put("z", p.getZ());
			m.put("distance", round1(Math.sqrt(p.distSqr(c))));
			out.add(m);
		}
		out.sort(Comparator.comparingDouble(m -> (double) m.get("distance")));
		return out.size() > 8 ? new ArrayList<>(out.subList(0, 8)) : out;
	}

	/**
	 * A block's human-readable type if it's worth perceiving, else null. The seam
	 * for "what counts as a notable object" — add block kinds here. Multi-block
	 * objects (bed, door) are reported once, from their primary half.
	 */
	private static String notableType(BlockState st) {
		Block b = st.getBlock();
		if (b instanceof BedBlock)
			return st.getValue(BedBlock.PART) == BedPart.HEAD ? b.getName().getString() : null;
		if (b instanceof DoorBlock)
			return st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? b.getName().getString() : null;
		if (b instanceof ChestBlock) return b.getName().getString();
		return null;
	}

	/** Unoccupied bed *heads* within {@code radius}, nearest first, capped at {@code max}. */
	private static List<BlockPos> findBeds(Level level, BlockPos center, int radius, int max) {
		List<BlockPos> beds = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(
				center.offset(-radius, -4, -radius), center.offset(radius, 4, radius))) {
			BlockState st = level.getBlockState(pos);
			if (!(st.getBlock() instanceof BedBlock)) continue;
			if (st.getValue(BedBlock.PART) != BedPart.HEAD) continue;   // count each bed once
			if (st.getValue(BedBlock.OCCUPIED)) continue;
			beds.add(pos.immutable());   // betweenClosed reuses a mutable cursor
		}
		beds.sort(Comparator.comparingDouble(b -> b.distSqr(center)));
		return beds.size() > max ? beds.subList(0, max) : beds;
	}

	/** Lie down in the nearest bed within reach, using MC's own sleep mechanic. */
	private static void sleepInNearbyBed(ServerLevel level, String selfId, Mob self) {
		if (self.isSleeping()) return;   // already asleep — nothing to do
		List<BlockPos> beds = findBeds(level, self.blockPosition(), BED_REACH, 1);
		if (beds.isEmpty()) {
			LOGGER.info("[biomata] {} tried to sleep but no bed within reach", selfId);
			return;
		}
		// Drop the stale move target so the walk loop doesn't drag it back out — and
		// so a target appearing later reads as a deliberate wake-and-move.
		BRIDGE.moveTargets.remove(selfId);
		self.startSleeping(beds.get(0));
		LOGGER.info("[biomata] {} sleeps at {}", selfId, beds.get(0));
	}

	/** Speech this agent heard since its last observation (consumed once). */
	private static List<Map<String, Object>> incomingMessages(String id) {
		List<String[]> heard = INBOX.remove(id);
		List<Map<String, Object>> out = new ArrayList<>();
		if (heard == null) return out;
		for (String[] h : heard) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("from", h[0]);
			m.put("text", h[1]);
			out.add(m);
		}
		return out;
	}

	private static Map<String, Object> pos(double x, double y, double z) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("x", x);
		m.put("y", y);
		m.put("z", z);
		return m;
	}

	/** Every other agent within {@link #NEARBY_AGENT_RADIUS}, with coords + distance. */
	private static List<Map<String, Object>> nearbyAgents(String selfId, Map<String, double[]> positions) {
		double[] self = positions.get(selfId);
		List<Map<String, Object>> out = new ArrayList<>();
		if (self == null) return out;
		for (var e : positions.entrySet()) {
			if (e.getKey().equals(selfId)) continue;
			double[] q = e.getValue();
			double dist = dist3(self, q);
			if (dist > NEARBY_AGENT_RADIUS) continue;
			Map<String, Object> n = new LinkedHashMap<>();
			n.put("id", e.getKey());
			n.put("name", e.getKey());
			n.put("position", pos(q[0], q[1], q[2]));
			n.put("distance", round1(dist));
			out.add(n);
		}
		return out;
	}

	/** Hostile mobs (creeper, zombie, …) within {@link #HOSTILE_RADIUS}, with type + distance. */
	private static List<Map<String, Object>> nearbyHostiles(Mob mob, Level level) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Monster m : level.getEntitiesOfClass(Monster.class, mob.getBoundingBox().inflate(HOSTILE_RADIUS))) {
			Map<String, Object> h = new LinkedHashMap<>();
			h.put("type", m.getName().getString());
			h.put("distance", round1(mob.distanceTo(m)));
			out.add(h);
		}
		return out;
	}

	/** Environmental hazards affecting the agent right now (empty = safe). */
	private static List<String> hazards(Mob mob) {
		List<String> out = new ArrayList<>();
		if (mob.isInWater()) out.add("in_water");
		if (mob.isInLava())  out.add("in_lava");
		if (mob.isOnFire())  out.add("on_fire");
		return out;
	}

	private static String timeOfDay(Level level) {
		long t = level.getOverworldClockTime() % 24000L;
		if (t < 1000 || t >= 23000) return "dawn";
		if (t < 12000) return "day";
		if (t < 14000) return "dusk";
		return "night";
	}

	private static double dist3(double[] a, double[] b) {
		double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static double round1(double v) {
		return Math.round(v * 10.0) / 10.0;
	}

	// ── Persistence ──────────────────────────────────────────────────────────
	// The roster (agent id, prompt, position) is saved to a per-world JSON file
	// so a host-authored sim survives a restart. Only host-side authoring state
	// is persisted here; engine-side runtime state (memory, social) would use the
	// engine's own snapshot/restore, a separate concern. Server-thread only.

	private static final class SavedAgent {
		String prompt;   // null = engine default persona
		String role;     // null = roleless (older rosters lack this — Gson leaves it null)
		String model;    // null = default model (older rosters lack this too)
		String goal;     // null = engine default goals (older rosters lack this too)
		double x, y, z;
		SavedAgent() {}
		SavedAgent(String prompt, String role, String model, String goal, double x, double y, double z) {
			this.prompt = prompt;
			this.role = role;
			this.model = model;
			this.goal = goal;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	private static Path rosterPath(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(ROSTER_FILE);
	}

	private static void saveRoster(MinecraftServer server) {
		Map<String, SavedAgent> roster = new LinkedHashMap<>();
		for (var e : AGENTS.entrySet()) {
			Mob p = e.getValue();
			if (p.isRemoved()) continue;
			roster.put(e.getKey(), new SavedAgent(
				PROMPTS.get(e.getKey()), AGENT_ROLE.get(e.getKey()), AGENT_MODEL.get(e.getKey()),
				AGENT_GOAL.get(e.getKey()), p.getX(), p.getY(), p.getZ()));
		}
		Path file = rosterPath(server);
		try {
			Files.writeString(file, GSON.toJson(roster));
		} catch (IOException ex) {
			LOGGER.error("[biomata] failed to save roster to {}", file, ex);
		}
	}

	private static void loadRoster(MinecraftServer server) {
		Path file = rosterPath(server);
		if (!Files.exists(file)) return;
		try (Reader r = Files.newBufferedReader(file)) {
			Type type = new TypeToken<Map<String, SavedAgent>>() {}.getType();
			Map<String, SavedAgent> roster = GSON.fromJson(r, type);
			if (roster != null) PENDING_LOAD.putAll(roster);
			LOGGER.info("[biomata] loaded {} saved agent(s), awaiting connection", PENDING_LOAD.size());
		} catch (IOException | RuntimeException ex) {
			LOGGER.error("[biomata] failed to load roster from {}", file, ex);
		}
	}

	/** Respawn every pending saved agent at its stored spot and re-register it. */
	private static void respawnSaved(MinecraftServer server) {
		// ponytail: assumes the overworld; multi-dimension agents would need the
		// saved dimension too. Fine until sims span dimensions.
		ServerLevel level = server.overworld();

		// The world saved our agent bodies in chunk data too, so on reload they
		// come back as orphans (not in AGENTS). Sweep any tagged body before
		// respawning from the roster, or we'd double up.
		// ponytail: only sees loaded chunks; an orphan whose chunk isn't loaded
		// yet slips through. Fine while agents live near spawn (loaded at start).
		List<Entity> orphans = new ArrayList<>();
		for (Entity ent : level.getAllEntities()) {
			if (ent.removeTag(AGENT_TAG)) orphans.add(ent);
		}
		orphans.forEach(Entity::discard);

		for (var e : PENDING_LOAD.entrySet()) {
			String id = e.getKey();
			if (AGENTS.containsKey(id)) continue;
			SavedAgent sa = e.getValue();
			Mob p = spawnerFor(sa.role).spawn(level, BlockPos.containing(sa.x, sa.y, sa.z));
			if (p == null) {
				LOGGER.warn("[biomata] failed to respawn saved agent '{}'", id);
				continue;
			}
			p.addTag(AGENT_TAG);
			tameAgentAi(p);
			AGENTS.put(id, p);
			if (sa.prompt != null) PROMPTS.put(id, sa.prompt);
			if (sa.role != null)   AGENT_ROLE.put(id, sa.role);
			if (sa.model != null)  AGENT_MODEL.put(id, sa.model);
			if (sa.goal != null)   AGENT_GOAL.put(id, sa.goal);
			// remove-then-register: engine may still hold this agent from the
			// previous connection, which would fail a plain register.
			reRegisterAgentFull(id);
		}
		PENDING_LOAD.clear();
		LOGGER.info("[biomata] restored saved agents; {} active ({} orphan bodies swept)",
			AGENTS.size(), orphans.size());
	}

	// ── Role helpers ──────────────────────────────────────────────────────────

	private static Role roleOf(String id) {
		String name = AGENT_ROLE.get(id);
		return name == null ? null : ROLES.get(name);
	}

	/** Capability tags an agent carries (from its role; empty if roleless). */
	private static List<String> capsFor(String id) {
		Role r = roleOf(id);
		return r == null ? List.of() : r.capabilities();
	}

	/** The body used for a role (its own spawner, or the default seam). */
	private static CharacterSpawner spawnerFor(String roleName) {
		Role r = roleName == null ? null : ROLES.get(roleName);
		return (r != null && r.spawner() != null) ? r.spawner() : characterSpawner;
	}

	/**
	 * Make the engine a body's sole *decision-maker* while keeping MC's *capabilities*.
	 *
	 * The distinction is the whole point. Vanilla AI mixes two things: decisions
	 * (choose to sleep at night, wander to a job site, panic and flee) and
	 * capabilities (open a door on the path, swim up instead of drowning, step up a
	 * block). Decisions fight the engine and must go; capabilities merely *execute*
	 * movement and should stay — reinventing them would be silly.
	 *
	 * So: clear every goal + brain behavior (kills the autonomous decisions), then
	 * re-add the involuntary locomotion capabilities as goals that pick no
	 * destination — they only react to the path the engine already set, so they
	 * can't compete. Voluntary behaviors (sleep, eat, work) are the engine's to
	 * choose and are exposed as actions (interactions) that call MC's own methods —
	 * restoring vanilla's *autonomous* versions here would both reintroduce the
	 * fighting and violate "behavior comes from the agent, not a hardcoded schedule".
	 *
	 * ponytail: FloatGoal + OpenDoorGoal are a sensible default for humanoid bodies;
	 * an aquatic or door-less custom body would want a different capability set —
	 * add a per-role capability-goal hook if that ever comes up.
	 */
	private static void tameAgentAi(Mob mob) {
		mob.getGoalSelector().removeAllGoals(g -> true);
		Brain<?> brain = mob.getBrain();
		brain.removeAllBehaviors();
		brain.clearMemories();

		mob.getNavigation().setCanOpenDoors(true);              // route paths through doors
		mob.getGoalSelector().addGoal(0, new FloatGoal(mob));   // swim up, don't drown
		mob.getGoalSelector().addGoal(1, new OpenDoorGoal(mob, true)); // open/close doors en route
	}

	/** Interaction names the agent may perform — the union of its capabilities' grants. */
	private static Set<String> grantedInteractions(String id) {
		Set<String> out = new LinkedHashSet<>();
		for (String cap : capsFor(id)) {
			List<String> acts = CAPABILITIES.get(cap);
			if (acts != null) out.addAll(acts);
		}
		return out;
	}

	/**
	 * The persona actually sent to the engine: the agent's character (custom
	 * prompt, else role default, else engine default) plus — if its role grants
	 * interactions — a note telling the LLM how to invoke them via `interact`.
	 */
	private static String composePersona(String id) {
		String base = PROMPTS.get(id);
		if (base == null) {
			Role r = roleOf(id);
			base = r != null ? r.persona() : null;   // null → engine DEFAULT_PERSONA
		}
		Set<String> granted = grantedInteractions(id);
		if (granted.isEmpty()) return base;
		StringBuilder sb = new StringBuilder(base == null ? "" : base);
		// Invocation shape: interaction name in parameters; for a *targeted* action
		// also set the top-level "target" to the other agent's id (the engine reads
		// interact's target from there, not from parameters). Each line below states
		// which form its action needs — generic over targeted vs self-directed.
		sb.append("\n\nYou can also perform these interactions with the 'interact' "
			+ "action, e.g. {\"action\": \"interact\", \"target\": \"<agent_id or empty>\", "
			+ "\"parameters\": {\"interaction\": \"<name>\"}}. Available:");
		for (String name : granted) {
			Action a = INTERACTIONS.get(name);
			if (a == null) continue;
			sb.append("\n- ").append(name)
				.append(a.targeted() ? " (set target to an agent id): " : " (no target needed): ")
				.append(a.description());
		}
		return sb.toString();
	}

	private static void registerAgentFull(String id)   { BRIDGE.registerAgent(id, composePersona(id), goalsFor(id), capsFor(id), AGENT_MODEL.get(id)); }
	private static void reRegisterAgentFull(String id) { BRIDGE.reRegisterAgent(id, composePersona(id), goalsFor(id), capsFor(id), AGENT_MODEL.get(id)); }

	/** An agent's goals, split from its `;`-separated goal text (empty = engine default). */
	private static List<String> goalsFor(String id) {
		String raw = AGENT_GOAL.get(id);
		if (raw == null || raw.isBlank()) return List.of();
		List<String> out = new ArrayList<>();
		for (String g : raw.split(";")) {
			String t = g.strip();
			if (!t.isEmpty()) out.add(t);
		}
		return out;
	}

	/**
	 * Resolve the interaction name the LLM emitted to a canonical interaction the
	 * agent is actually granted — matching the name or any alias, case-insensitively.
	 * Scoped to the agent's granted set, so an alias like "use" can't collide across
	 * unrelated actions. Returns null if nothing the agent can do matches.
	 */
	private static String resolveGranted(String id, String emitted) {
		if (emitted == null || emitted.isEmpty()) return null;
		for (String canonical : grantedInteractions(id)) {
			if (canonical.equalsIgnoreCase(emitted)) return canonical;
			Action a = INTERACTIONS.get(canonical);
			if (a == null) continue;
			for (String alias : a.aliases()) if (alias.equalsIgnoreCase(emitted)) return canonical;
		}
		return null;
	}

	/** Apply one `interact` decision, gated by (and alias-resolved against) the agent's capabilities. */
	private static void dispatchInteraction(String id, String emitted, String targetId) {
		Mob self = AGENTS.get(id);
		if (self == null || self.isRemoved()) return;
		String name = resolveGranted(id, emitted);
		if (name == null) {
			LOGGER.info("[biomata] {} tried '{}' but its role grants no matching interaction — ignored", id, emitted);
			return;
		}
		Action action = INTERACTIONS.get(name);
		if (action == null) return;
		Mob target = null;
		if (action.targeted()) {   // self-directed actions (sleep, …) need no target
			target = (targetId == null || targetId.isEmpty()) ? null : AGENTS.get(targetId);
			if (target == null || target.isRemoved()) {
				LOGGER.info("[biomata] {} '{}' has no resolvable target '{}' — skipped", id, name, targetId);
				return;
			}
		}
		action.handler().apply((ServerLevel) self.level(), id, self, target);
		LOGGER.info("[biomata] {} interacts: {}{}", id, name, action.targeted() ? " -> " + targetId : "");
	}

	// ── Commands ──────────────────────────────────────────────────────────────

	private static int cmdSpawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return doSpawn(ctx.getSource(), StringArgumentType.getString(ctx, "id"), null, null);
	}

	private static int cmdSpawnRole(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return doSpawn(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
			StringArgumentType.getString(ctx, "role"), null);
	}

	private static int cmdSpawnRoleModel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return doSpawn(ctx.getSource(), StringArgumentType.getString(ctx, "id"),
			StringArgumentType.getString(ctx, "role"), StringArgumentType.getString(ctx, "model"));
	}

	private static int doSpawn(CommandSourceStack src, String id, String roleName, String model) throws CommandSyntaxException {
		if (AGENTS.containsKey(id)) {
			src.sendFailure(Component.literal("agent already exists: " + id));
			return 0;
		}
		if (roleName != null && !ROLES.containsKey(roleName)) {
			src.sendFailure(Component.literal(
				"unknown role: " + roleName + " (known: " + String.join(", ", ROLES.keySet()) + ")"));
			return 0;
		}
		ServerPlayer player = src.getPlayerOrException();
		ServerLevel level = player.level();
		BlockPos pos = player.blockPosition().offset(2, 0, 0);
		Mob p = spawnerFor(roleName).spawn(level, pos);
		if (p == null) {
			src.sendFailure(Component.literal("spawn failed"));
			return 0;
		}
		p.addTag(AGENT_TAG);
		tameAgentAi(p);
		AGENTS.put(id, p);
		if (roleName != null) AGENT_ROLE.put(id, roleName);
		if (model != null)    AGENT_MODEL.put(id, model);
		registerAgentFull(id);
		saveRoster(src.getServer());
		String suffix = (roleName != null ? " as " + roleName : "") + (model != null ? " [" + model + "]" : "");
		src.sendSuccess(() -> Component.literal("spawned agent '" + id + "'" + suffix), false);
		return 1;
	}

	private static int cmdPrompt(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "id");
		String text = StringArgumentType.getString(ctx, "text");
		CommandSourceStack src = ctx.getSource();
		if (!AGENTS.containsKey(id)) {
			src.sendFailure(Component.literal("no such agent: " + id));
			return 0;
		}
		PROMPTS.put(id, text);
		reRegisterAgentFull(id);   // persona changed; keep the agent's role capabilities
		saveRoster(src.getServer());
		src.sendSuccess(() -> Component.literal("set persona for '" + id + "'"), false);
		return 1;
	}

	private static int cmdGoal(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "id");
		String text = StringArgumentType.getString(ctx, "text");
		CommandSourceStack src = ctx.getSource();
		if (!AGENTS.containsKey(id)) {
			src.sendFailure(Component.literal("no such agent: " + id));
			return 0;
		}
		// Goals give the agent a reason to act — separate multiple with ';'. Sets
		// personality.goals, which the engine renders into the agent's character.
		AGENT_GOAL.put(id, text);
		reRegisterAgentFull(id);
		saveRoster(src.getServer());
		LOGGER.info("[biomata] {} goals -> {}", id, goalsFor(id));   // confirm parse + wire
		src.sendSuccess(() -> Component.literal("set goal(s) for '" + id + "'"), false);
		return 1;
	}

	private static int cmdRemove(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "id");
		CommandSourceStack src = ctx.getSource();
		Mob p = AGENTS.remove(id);
		if (p == null) {
			src.sendFailure(Component.literal("no such agent: " + id));
			return 0;
		}
		if (!p.isRemoved()) p.discard();
		PROMPTS.remove(id);
		AGENT_ROLE.remove(id);
		AGENT_MODEL.remove(id);
		AGENT_GOAL.remove(id);
		INBOX.remove(id);
		BRIDGE.unregisterAgent(id);
		saveRoster(src.getServer());
		src.sendSuccess(() -> Component.literal("removed agent '" + id + "'"), false);
		return 1;
	}

	private static int cmdList(CommandContext<CommandSourceStack> ctx) {
		if (AGENTS.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("agents: (none)"), false);
			return 1;
		}
		List<String> rows = new ArrayList<>();
		for (String id : AGENTS.keySet()) {
			String role = AGENT_ROLE.get(id);
			String model = AGENT_MODEL.get(id);
			rows.add(id
				+ (role != null ? " (" + role + ")" : "")
				+ (model != null ? " [" + model + "]" : ""));
		}
		ctx.getSource().sendSuccess(() -> Component.literal("agents: " + String.join(", ", rows)), false);
		return 1;
	}

	private static int cmdRoles(CommandContext<CommandSourceStack> ctx) {
		String roles = ROLES.isEmpty() ? "(none)" : String.join(", ", ROLES.keySet());
		ctx.getSource().sendSuccess(() -> Component.literal("roles: " + roles), false);
		return 1;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
