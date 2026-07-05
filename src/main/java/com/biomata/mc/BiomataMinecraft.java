package com.biomata.mc;

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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class BiomataMinecraft implements ModInitializer {
	public static final String MOD_ID = "biomata-minecraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final BiomataBridge BRIDGE = new BiomataBridge();

	/**
	 * Character palette for `/biomata spawn <id> <type>`. Biomata drives NPC
	 * characters only — humanoid, non-hostile. Animals and monsters are out.
	 */
	private static final Map<String, EntityType<? extends Mob>> CHARACTER_TYPES = Map.of(
		"villager", EntityTypes.VILLAGER,
		"wandering_trader", EntityTypes.WANDERING_TRADER);

	/** agentId -> the entity it drives. Server-thread only. */
	private static final Map<String, Mob> AGENTS = new LinkedHashMap<>();

	private static int serverTicks;

	@Override
	public void onInitialize() {
		LOGGER.info("Hello Fabric world!");

		// Connect to biomata-engine when a world/server starts.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> BRIDGE.connect());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			BRIDGE.disconnect();
			AGENTS.clear();
		});

		// Authoring UI: `/biomata spawn|remove|list`. Agents are created by the
		// player at runtime, not hardcoded.
		CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
			dispatcher.register(Commands.literal("biomata")
				.then(Commands.literal("spawn")
					.then(Commands.argument("id", StringArgumentType.word())
						.executes(ctx -> cmdSpawn(ctx, "villager"))
						.then(Commands.argument("type", StringArgumentType.word())
							.suggests((c, b) -> {
								CHARACTER_TYPES.keySet().forEach(b::suggest);
								return b.buildFuture();
							})
							.executes(ctx -> cmdSpawn(ctx, StringArgumentType.getString(ctx, "type"))))))
				.then(Commands.literal("prompt")
					.then(Commands.argument("id", StringArgumentType.word())
						.then(Commands.argument("text", StringArgumentType.greedyString())
							.executes(BiomataMinecraft::cmdPrompt))))
				.then(Commands.literal("remove")
					.then(Commands.argument("id", StringArgumentType.word())
						.executes(BiomataMinecraft::cmdRemove)))
				.then(Commands.literal("list")
					.executes(BiomataMinecraft::cmdList))));

		// Each server tick, walk every agent toward its engine target; drive the
		// engine ~every 5s (never while a tick is still awaiting the LLM).
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (AGENTS.isEmpty()) return;

			for (var e : AGENTS.entrySet()) {
				Mob p = e.getValue();
				if (p.isRemoved()) continue;
				double[] t = BRIDGE.moveTargets.get(e.getKey());
				if (t != null && p.getNavigation().isDone()) {
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
				Map<String, double[]> positions = new LinkedHashMap<>();
				for (var e : AGENTS.entrySet()) {
					Mob p = e.getValue();
					if (!p.isRemoved()) {
						positions.put(e.getKey(), new double[] {p.getX(), p.getY(), p.getZ()});
					}
				}
				BRIDGE.sendTick(positions);
			}
		});
	}

	private static int cmdSpawn(CommandContext<CommandSourceStack> ctx, String type) throws CommandSyntaxException {
		String id = StringArgumentType.getString(ctx, "id");
		CommandSourceStack src = ctx.getSource();
		if (AGENTS.containsKey(id)) {
			src.sendFailure(Component.literal("agent already exists: " + id));
			return 0;
		}
		EntityType<? extends Mob> entityType = CHARACTER_TYPES.get(type.toLowerCase());
		if (entityType == null) {
			src.sendFailure(Component.literal(
				"unknown character type '" + type + "'. options: " + String.join(", ", CHARACTER_TYPES.keySet())));
			return 0;
		}
		ServerPlayer player = src.getPlayerOrException();
		ServerLevel level = player.level();
		BlockPos pos = player.blockPosition().offset(2, 0, 0);
		Mob p = entityType.spawn(level, pos, EntitySpawnReason.COMMAND);
		if (p == null) {
			src.sendFailure(Component.literal("spawn failed"));
			return 0;
		}
		AGENTS.put(id, p);
		BRIDGE.registerAgent(id, null);
		src.sendSuccess(() -> Component.literal("spawned agent '" + id + "' (" + type + ")"), false);
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
		BRIDGE.setPrompt(id, text);
		src.sendSuccess(() -> Component.literal("updated prompt for '" + id + "'"), false);
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
		BRIDGE.unregisterAgent(id);
		src.sendSuccess(() -> Component.literal("removed agent '" + id + "'"), false);
		return 1;
	}

	private static int cmdList(CommandContext<CommandSourceStack> ctx) {
		String list = AGENTS.isEmpty() ? "(none)" : String.join(", ", AGENTS.keySet());
		ctx.getSource().sendSuccess(() -> Component.literal("agents: " + list), false);
		return 1;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
