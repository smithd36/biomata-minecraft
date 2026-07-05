# biomata-minecraft

A Minecraft (Java Edition, Fabric) mod that acts as a **host client** for the
[biomata-engine](../biomata-engine), driving Minecraft entities as AI agents in
a simulated world.

## The architecture (already decided)

```
Minecraft (Fabric mod, Java)  ──JSON/WebSocket:8765──►  biomata-engine (Python, unchanged)
   pushes: entity positions, world state (observations)
   applies: movement/action commands it gets back
```

The engine is done. It's a standalone Python process speaking JSON over a
WebSocket (port 8765). The mod plays the same role the Unity SDK does: a dumb
host client. No engine changes. The only new territory is the Minecraft mod
toolchain.

Reference implementation: `../biomata-unity-sdk` — `Runtime/Transport` and
`Runtime/Models` hold the same JSON protocol, in C#. Translate C# → Java.
Authoritative wire spec: `../biomata-engine/docs/websocket-protocol.md`.

## End goal

A running Minecraft world where entities are controlled per-tick by AI brains
in biomata-engine — a fully simulated agent world, observable and playable
inside Minecraft.

## Environment (confirmed)

- Modding launcher: `C:\XboxGames\Minecraft Launcher` (official Mojang launcher,
  manages `%APPDATA%\.minecraft`). **Use this.**
- Do NOT mod the Xbox game tile: `C:\XboxGames\Minecraft- Java Edition`.
- Target: Minecraft **26.2** → requires **JDK 25** (confirmed: build fails on 21
  with "release version 25 not supported"). Loader 0.19.3, Fabric API 0.154.0+26.2.
- Loader: **Fabric** (not Forge).

## Phases — each finishes before the next starts

### Phase 0 — Toolchain ✅
The real "game dev" hurdle. Clear it before touching the engine.
- [x] Install JDK 25 (26.2 requires 25, not 21)
- [ ] Install IntelliJ IDEA Community (optional — Gradle CLI works without it)
- [x] Extract the official Fabric template into this repo
- [x] Confirm Gradle resolves — `./gradlew build` → BUILD SUCCESSFUL

### Phase 1 — Hello world ✅
- [x] Build the template
- [x] Launch a dev Minecraft (`./gradlew runClient`)
- [x] Saw `Hello Fabric world!` in the log on startup
- [x] Confirms the build → run loop works end to end
- Note: dev instance auth/Realms 401 errors are expected & harmless.

### Phase 2 — The bridge (the real prototype)
Prove the whole architecture with the smallest possible slice.
Sub-staged cheapest-first so socket / LLM / entity bugs don't pile up at once.

Protocol (from `biomata-engine/src/transport/websocket/`):
- Connect → server pushes `hlo`. Client sends `req{type,v,id,method,params}`,
  gets `res{ok,result}`. Server also pushes `evt`.
- Host-driven loop: `register_agent` → per tick `tick{agent_observations}` →
  response `decisions[]` with `action` (`idle/move/speak/interact`) + `parameters`.
- Java side uses JDK stdlib `java.net.http.WebSocket` — NO gradle dependency.
- Start engine: `biomata-ws --config examples/host_owned/sim.yaml --port 8765`

**2a — Prove the wire** ✅
- [x] Java `WebSocket` connects to `ws://localhost:8765` on server start
- [x] Logged the `hlo` frame (host_driven, 12 capabilities)
- [x] `health_check` round-tripped ok=true
- Uses JDK stdlib `java.net.http.WebSocket`, hand-built JSON. No deps.

**2b — One agent, one pig** ✅
- [x] `register_agent` for one agent (`pig-1`, IdleBrain)
- [x] Spawn one vanilla pig next to the player on join

**2c — Make it walk**
- [x] Register IdleBrain with brain_config `{action:"move", parameters:{target_x,target_z}}`
      — deterministic `move` every tick, NO Ollama. (brain_cls(**brain_config))
- [x] Each game second: send `tick` with pig position; apply `move` decision
      via `pig.getNavigation().moveTo(...)`
- [x] IN-GAME CHECK: pig walked a straight line toward the engine's target ✅
- Locomotion note: MC pathfinding only reaches ~dozens of blocks, so the host
  steps toward the far engine target in ~8-block hops as each segment finishes.
  Engine decides direction; host handles locomotion (the intended split).
- KNOWN MINOR BUG (fix in Phase 3): onFrame treats any `res` with `agent_id`
  as a register reply, so the remove_agent response on disconnect re-logs
  "agent registered". Harmless now; fix by correlating responses via req `id`.

## PHASE 2 COMPLETE ✅ — the architecture is proven end to end.
Minecraft entity driven by the Python biomata-engine over WebSocket.

### Phase 3 — Scale up
No unknowns left after Phase 2 — just volume and mapping.

**#1 — Multiple entities**
- [x] `Map<agentId, Pig>` replaces the single pig; spawn PIG_COUNT (5) on join
- [x] Each pig its own agent, fanned out (compass directions) so movement is visible
- [x] One `tick` per second carries ALL pigs' positions; decisions applied per-agent
- [x] Replies correlated by request `id` (pending map) — fixes the 2c remove/register
      mislabel bug; register/tick/remove never confused
- [x] IN-GAME CHECK: 5 pigs spawned and dispersed in different directions ✅
      Clean shutdown, no phantom register log — id-correlation confirmed.

**#3 — A thinking brain** ✅ (done before #2 — LLM chosen over deterministic)
- [x] Register `ollama` brain (`OllamaLLMBrain`), model `qwen2.5:14b`, per-agent
- [x] Ticks paced to LLM latency (skip while a tick is in flight); ~5s cadence
- [x] Each decision logged: `pig-N -> move {target_x,target_z}`
- [x] VERIFIED: Ollama `/api/generate 200 ~1.4s`, engine httpx call, pigs walk to
      LLM-chosen coords. Full loop: MC pos → engine → LLM → action → pig moves.

**#2 — Richer observations** (optional next)
- [ ] Currently each agent only observes its own position (LLM picks nearby coords,
      so all pigs cluster). Add `nearby_agents` / blocks so brains can interact
      → emergent social behavior instead of parallel wandering.

## PHASE 3 #1 + #3 COMPLETE ✅ — LLM-driven Minecraft agents via biomata-engine.

### Phase 4 — In-game authoring UI (commands, not GUI)
Goal: users create their own simulations in-game — spawn agents, set prompts,
pick actions. Medium = Fabric command tree (native, free parsing/tab-complete);
a GUI screen could sit on the same backend later. Auto-spawn removed.

Hard part deferred: authoring genuinely NEW action *behaviors* at runtime needs
scripting. Lazy plan instead = a fixed palette of mod-implemented actions users
select + describe per agent. No runtime Java authoring.

**MVP — command skeleton** ✅ (built, pending in-game test)
- [x] `/biomata spawn <id>` — spawn a pig, register an LLM agent, start driving it
- [x] `/biomata remove <id>` — despawn + unregister
- [x] `/biomata list` — list active agent ids
- [x] IN-GAME CHECK: spawn/list/remove all work from chat ✅

**Next commands** (not built)
- [ ] `/biomata prompt <id> <text...>` — set system prompt (remove+re-register)
- [ ] `/biomata model <id> <model>` / `entity <type>` at spawn
- [ ] `/biomata actions <id> <action> on|off` — from a fixed palette
- [ ] `/biomata save <name> | load <name>` — persist a sim to json

## Guiding principle

Build the one-pig bridge before the world. Every phase runs before the next
begins. The scary part (the simulation) is already built; the mod is plumbing.
