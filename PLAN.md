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
Authoritative wire spec: **https://originfoundry.dev/docs/transport** (the live
engine docs; the old `docs/websocket-protocol.md` file path no longer exists).
The implementation source of truth is `../biomata-engine/src/transport/websocket/`.

## End goal

A running Minecraft world where entities are controlled per-tick by AI brains
in biomata-engine — a fully simulated agent world, observable and playable
inside Minecraft.

## North star — a reusable simulation framework for human simulacra

The ambition: a **reusable Minecraft simulation framework** on top of
biomata-engine, for research/study *and* gamedev — as close to **human
simulacra** (Stanford *Generative Agents*, Park et al. 2023 — "Smallville") as
the engine allows, embodied in Minecraft. Truly free-willed AI agents that
perceive, talk, remember, and relate — not scripted NPCs.

**The framing that governs everything:** biomata-minecraft is the *Minecraft
twin of the Unity SDK*. The engine already IS the simulation framework
(`ExternalWorld`: host pushes observations, engine returns commands). The mod is
the **host adapter** — so most of our work is *faithfully implementing the host
side of contracts the engine already defines*, not inventing new machinery. The
simulacra scaffolding already exists engine-side: `SimpleMemory` (per-agent
memory), `WeightedGraphSocial` (relationship graph), `ConversationInbox` (agents
exchange messages within a tick). What makes it simulacra rather than parallel
wandering is that agents perceive each other, speak, remember, and relate — all
supported, currently all unused by us.

### The gap — what the engine offers vs. what we use today

| | Engine offers | We use |
|---|---|---|
| Observations | `position`, `nearby_agents`, composable providers | `position` **only** |
| Actions | idle, move, speak, interact, greet, follow, trade, eat, work, sleep, patrol… (15) | `move` **only** |
| Roles/capabilities | gate which actions each agent sees | bypassed (raw `brain_class`, empty caps) |
| Memory / social / inbox | built-in, snapshotable | untouched |
| Snapshot/restore | engine methods `snapshot`/`restore` | not wired (don't reimplement save/load) |

The engine's own `host_owned/sim.yaml` declares `nearby_agents` for every role —
we don't even meet that baseline. That is why agents wander in parallel: they are
blind to each other.

### Roadmap to simulacra (leverage order)

1. **`nearby_agents` observations** — the unlock. Until agents perceive each
   other, `speak`/`greet`/`follow`/`interact` have no `target` to name. Turns a
   parallel crowd into a social field. (This is the old Phase 3 #2 gap.)
2. **Apply `speak`** — render the message in-game (server chat / nametag). It's
   `kind: hybrid`: engine already routes it through inbox + social graph; the
   host just displays it. The visible "they're talking" moment.
3. **Roles + capabilities** — register by role so the engine exposes the full
   action vocabulary; the mod becomes a role system, not one hardcoded wanderer.
4. **Generalize into a framework** — an observation assembler + an action
   dispatcher mirroring Unity's `ObservationProvider`s and `ActionHandlerBase`,
   so modders extend by adding one class, not editing a switch. *After* 1–3
   prove the shape, not before.

Memory / reflection / planning (the paper's core) come later and are mostly
*engine-side brains* — likely not our code at all.

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
