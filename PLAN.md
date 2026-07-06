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
| Observations | `position`, `nearby_agents`, composable providers | position, nearby_agents/hostiles, hazards, time/light/health, asleep, nearby_objects, incoming_messages ✅ |
| Actions | idle, move, speak, interact (4 — the engine ships no more; "15" was aspirational) | move, speak, interact ✅ |
| Roles/capabilities | tags gate engine obs/action *schemas* (host provides obs in host-owned, so obs gating is moot; no gated actions ship) | host-defined roles/caps + capability-gated `interact` actions ✅ |
| Memory / social / inbox | built-in, snapshotable | untouched |
| Snapshot/restore | engine methods `snapshot`/`restore` | not wired (don't reimplement save/load) |

The engine's own `host_owned/sim.yaml` declares `nearby_agents` for every role —
we don't even meet that baseline. That is why agents wander in parallel: they are
blind to each other.

### Roadmap to simulacra (leverage order)

1. **`nearby_agents` observations** ✅ (built, pending in-game check) — the
   unlock. Until agents perceive each other, `speak`/`greet`/`follow`/`interact`
   have no `target` to name. Turns a parallel crowd into a social field. (Old
   Phase 3 #2 gap.) `BiomataBridge.sendTick` now attaches `nearby_agents` to each
   observation: every peer within `NEARBY_RADIUS` (32 blocks) with `id`, `name`,
   `position`, `distance` — computed from the positions map already passed in, so
   no new entity access or thread surface. Engine consumes it as-is (HostedWorld).
   Default prompt updated to reference nearby agents.

   **Environmental awareness** ✅ (built, pending in-game check) — extended
   observations beyond peers so behaviour can be situation-driven: each tick now
   also carries `nearby_hostiles` (mobs within 16 blocks, type + distance),
   `hazards` (`in_water`/`in_lava`/`on_fire`), `time_of_day`, `light_level`, and
   `health`. In doing so, **observation assembly moved to the server thread**
   (`BiomataMinecraft.buildObservation`, one small helper per slice — the
   extensible provider shape); `BiomataBridge` is now pure transport (takes a
   pre-built `Map<String,Object>` and Gson-serializes it as-is). Needs (hunger/
   energy) deliberately deferred — those are engine-modelled (`SelfNeedsProvider`
   + needs extension), not host-readable from a villager.
2. **Apply `speak`** ✅ (built, pending in-game check) — `kind: hybrid`: engine
   already routes it through inbox + social graph; the host just displays it.
   `BiomataBridge` enqueues `speak` decisions (`text`/`message` param) on a
   concurrent `speech` queue on the WS thread; `END_SERVER_TICK` drains it and
   broadcasts `<id> text` to chat on the server thread (same thread-split pattern
   as `moveTargets`). Default prompt now invites speaking on approach. Rendered as
   server chat for now — floating nametag "speech bubble" is a later polish.

   Also this pass: **spawn made non-opinionated.** The hardcoded entity palette
   (`CHARACTER_TYPES` + `<type>` command arg) is gone; spawning goes through a
   single public `CharacterSpawner` seam (defaults to villager) a downstream mod
   can reassign. Per-agent type selection (prompt / command) will layer on later.
3. **Roles + capabilities** ✅ (built, pending in-game check) — NB the premise
   changed on contact with the engine: it ships only **4** verbs
   (idle/move/speak/interact), **none capability-gated**, and new first-class verbs
   need Python handlers engine-side (out of scope). So the role vocabulary is
   **host-defined** and rides the generic `interact` verb. Three public seam maps in
   `BiomataMinecraft` — `INTERACTIONS` (name → handler+description), `CAPABILITIES`
   (tag → interactions), `ROLES` (name → body+persona+caps) — populated with example
   built-ins (a `companion` that can `follow`). `/biomata spawn <id> [role]` embodies
   the role, registers its capability tags, and composes a persona that tells the LLM
   how to invoke its interactions; `interact` decisions are dispatched host-side,
   **gated** by the agent's granted interactions. `/biomata roles` lists them; role
   persists in the roster. Runtime authoring (command/config) is the deferred layer.
   The mod is now a role system, not one hardcoded wanderer.
   **Voluntary behaviors as engine actions (sleep)** ✅ (verified in-game) — the
   "use MC's own code, engine decides *when*" pattern. `tameAgentAi` keeps MC
   *capabilities* (doors via `OpenDoorGoal`, swim via `FloatGoal`) while removing
   autonomous *decisions*; voluntary behaviors are then engine-chosen actions.
   `sleep` is the first: a self-directed interaction (new `targeted` flag on
   `Action`, target null) that calls MC's `startSleeping`. It's an **instantaneous
   handler** — navigation stays the engine's job, so the LLM `move`s to a bed, then
   `sleep`s; `asleep` is observed so the LLM owns the wake decision (a fresh move
   target wakes it). Adds no host state (sleep-state is MC-native). Pipeline is
   generic: eat/sit/work are just more `INTERACTIONS` entries.

   **General perception, not deterministic affordances** ✅ (verified in-game) —
   the design directive that governs all future actions: the host reports **facts**,
   the LLM makes **decisions**. Replaced the specific `nearby_beds` slice (and
   rejected `at_bed`/`time_to_wake` booleans) with a general `nearby_objects`
   observation (everything notable within `OBJECT_SIGHT`, tagged `notableType` —
   bed/door/chest/…). The LLM reasons over it ("bed nearby + night → sleep";
   "asleep + day → move to get up"). Rule: perception is general not per-action; an
   action's *description* says what it is and how to invoke it, never *when* to
   choose it; the model is the swappable/improvable backend, so reasoning gaps are
   fixed with a better model, not host determinism. Invocation plumbing (grant-scoped
   **aliases** so `use`/`rest` resolve to `sleep`) is how-to-emit, not what-to-decide,
   and stays.

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

- [x] `/biomata prompt <id> <text...>` — set system prompt (remove+re-register)

**Persistence** ✅ (verified in-game) — the host-authored roster survives a restart
automatically (no explicit save/load command). Each agent's `{prompt, position}`
is written to a per-world JSON file (`<world>/biomata_agents.json`) on every
spawn/remove/prompt and on server stop. On start, the roster loads into
`PENDING_LOAD`; the tick loop respawns + re-registers each agent once the bridge is
connected (handles the world-ready vs socket-ready race on the server thread). Only
host authoring state is persisted; engine runtime state (memory, social) would use
the engine's own snapshot/restore — separate concern.

Restart hardening (found + fixed in-game): the roster — not the world save — is the
source of truth for agent bodies. Two hazards handled in `respawnSaved`: (1) the
world save also persists the villager entity, so it returns as an orphan and used
to double up → every body carries a `biomata_agent` scoreboard tag and orphans are
swept before respawn; (2) the engine may still hold the agent from the dropped
connection → respawn uses remove-then-register (`reRegisterAgent`), else a plain
register fails `AGENT_EXISTS` and the sim silently freezes (no ticks).

**Conversation (host-routed)** ✅ (verified in-game) — agents greeted
but never answered because a listener never received the speaker's words. In
host-owned mode the host's observation is authoritative and the engine's
`ConversationInbox`/`IncomingMessagesProvider` aren't in our path, so routing speech
is the host's job. When a `speak` is drained, besides chat it's pushed into every
nearby agent's `INBOX` (within `NEARBY_AGENT_RADIUS`); `buildObservation` drains
that into `incoming_messages` (`[{from,text}]`), which the brain renders as
`=== MESSAGES RECEIVED ===` and replies to. Cross-tick continuity is the engine's
own per-agent memory (stores a "heard:" line each tick). SYSTEM_INSTRUCTIONS nudges
agents to answer received messages rather than re-greet. ponytail: broadcast to
earshot, speaker `target` ignored — add targeting if crosstalk gets noisy.

**Per-agent personality** ✅ (verified in-game) — the differentiation
lever. Engine renders a uniform `system_prompt` (mechanics) and a *separate*
per-agent `personality` block (`YOUR CHARACTER`) into each LLM call. So the mod now
sends a fixed `SYSTEM_INSTRUCTIONS` for all, and `/biomata prompt <id> <text>` sets
that agent's **persona** (`personality.backstory`), not the system prompt. Was the
root cause of uniform behavior (all `move`, never `speak`): identical system_prompt
*and* identical hardcoded persona. Behavior emerges from persona — nothing forced
host-side.

**Per-agent goals** ✅ (built) — persona says who an agent *is*; goals say what
it's *trying to do*. The sleep saga exposed the gap: agents were purely reactive (a
"tired villager" with no daytime drive slept all day). Same lever as persona —
`/biomata goal <id> <text>` (`;`-separated → list) sets `personality.goals`, which
the engine renders as a `Goals:` bullet list in the character block. Gives agents a
*reason* to choose actions non-deterministically. Persists in the roster. (`cmdGoal`
logs the parsed goals so a bad parse is distinguishable from a bad decision.)

**Diagnosis that goals surfaced — action vocabulary is the bottleneck.** Testing
goals produced "all the agent does is wander and sleep." Root causes: (1) a `-coder`
model was used — wrong tool for character/agentic reasoning; use a general/instruct
model. (2) **A goal can only be pursued through the actions the agent has.** With
`move`/`speak`/`sleep`/`follow`, only movement/social/rest goals are *expressible*;
a "farm"/"work" goal has no outlet and collapses to wander+sleep, and a social goal
needs a second agent to fire on. This is goals working as designed: they name the
next thing to build. So goals must currently be written to fit existing actions
("wander the village and greet everyone; sleep at night"), and the roadmap edge is
now clear → **add concrete actions for goals to reach for.**

### Current edge — what's next (in order)

1. **A general/instruct model + a second agent + goals that fit current actions** —
   the re-test that validates goals before adding surface. Not code.
2. **A 3rd concrete action** (e.g. `eat`, or a generic `use <object>` leaning on
   MC's block-interaction) — gives goals something to express through. Same generic
   `INTERACTIONS`+capability+role shape as `sleep`; no per-verb plumbing.
3. **Step 4 — generalize into a framework** (observation assembler + action
   dispatcher, below) once a 3rd action confirms the shape holds.

Deferred: runtime role authoring (`/biomata role add …` or world JSON);
`/biomata model <id>` hot-swap; `/biomata entity <type>`.

**Per-agent model** ✅ (verified in-game) — `/biomata spawn <id> [role] [model]`
overrides the Ollama model per agent (`llm_config.model`; default qwen2.5:14b).
Confirmed the thesis: gemma is more reliable but slow, qwen 14B faster but weaker —
cognition scales with the model, so reasoning gaps are a backend concern, not an
architecture one.

**Next commands** (not built)
- [ ] `/biomata entity <type>` at spawn — per-agent body (layers on `characterSpawner`)
- [ ] `/biomata model <id> <model>` — hot-swap a running agent's model (spawn-time exists)
- [ ] `/biomata actions <id> <action> on|off` — from a fixed palette

## Guiding principle

Build the one-pig bridge before the world. Every phase runs before the next
begins. The scary part (the simulation) is already built; the mod is plumbing.
