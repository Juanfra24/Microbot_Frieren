# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Private fork of [chsami/Microbot](https://github.com/chsami/Microbot) — an OSRS automation client built on RuneLite. Telemetry removed, security hardened, integrated with Command Center.

## Build & Test

```bash
./gradlew :client:compileJava              # Quick compile check
./gradlew buildAll                         # Full build (all included builds)
./gradlew :client:assemble                 # Shaded JAR → runelite-client/build/libs/
```

**Test tasks** (all others are disabled by default):

```bash
./gradlew :client:runUnitTests             # CI-safe unit tests; includes ClientThreadGuardrailTest
./gradlew :client:runTests                 # Full test run with timezone setup
./gradlew :client:runDebugTests            # Debug test run
./gradlew :client:runIntegrationTest       # Requires live running game client
./gradlew :client:runClientThreadScanner   # Regenerates docs/client-thread-manifest.md + .tsv
./gradlew :client:regenerateClientThreadGuardrailBaseline  # Update guardrail allow-list after intentional refactors
```

- **Java:** 17+ (JDK required)
- **Build system:** Gradle Kotlin DSL — composite build: `:client` = `runelite-client/`, `:jshell` = `runelite-jshell/`, plus `includeBuild` for `cache`, `runelite-api`, `runelite-gradle-plugin`
- **Version:** `gradle.properties` → `microbot.version`
- **CI:** `.github/workflows/build.yml` — semantic versioning (v1.0.N auto-increment), shaded JAR, GitHub Release

## Project Layout

```
runelite-client/src/main/java/net/runelite/client/plugins/microbot/
├── commandcenter/                    # Our custom code (Command Center integration)
│   ├── AutoLoginPlugin.java          # Profile-based credential injection
│   ├── ScriptAutoStartPlugin.java    # Plugin activation by @PluginDescriptor name
│   ├── CredentialRedactor.java       # Strip credentials from logs
│   ├── status/                       # Status API (localhost HTTP)
│   │   ├── StatusApiServer.java      # Ephemeral port, port file discovery
│   │   ├── StatusApiHandler.java     # /status + /health endpoints
│   │   └── BotStatusModel.java       # JSON response builder (schema v1)
│   └── scripts/                      # CC Bot Scripts
│       ├── core/                     # Framework
│       │   ├── CCBehavior.java       # Behavior interface (priority-based)
│       │   ├── CCScript.java         # Generic base class with state machine
│       │   └── behaviors/            # 6 reusable behaviors
│       ├── woodcutting/              # 5 files (Script, Config, Plugin, Overlay, WoodcuttingTree enum)
│       ├── mining/                   # 5 files (+ MiningRock enum)
│       ├── fishing/                  # 5 files (+ FishType enum)
│       ├── cooking/                  # 5 files (+ CookableFood enum)
│       └── combat/                   # 4 files (Script, Config, Plugin, Overlay)
├── api/                              # Queryable API (v2.1.0) — fluent entity caches
├── util/                             # Legacy utilities (Rs2Inventory, Rs2Bank, Rs2Walker, etc.)
├── example/                          # Example scripts
├── Microbot.java                     # Singleton: caches, utilities, lifecycle
├── Script.java                       # Base script class (upstream)
└── MicrobotPlugin.java               # Main RuneLite plugin
```

Tests mirror this structure under `runelite-client/src/test/java/.../commandcenter/`.

## Command Center Integration

### Status API (`commandcenter/status/`)

Localhost-only HTTP server for Command Center to poll bot state:

- **StatusApiServer** - Binds `127.0.0.1:0` (ephemeral port), writes port to `--status-port-file` path
- **StatusApiHandler** - `GET /status` returns JSON (schema v1), `GET /health` returns 200
- **BotStatusModel** - Thread-safe reads: character, login state, script info, player stats, XP, uptime

CLI flags (via jopt-simple → System properties):
- `--status-port-file <path>` - Where to write the port number
- `--cc-profile-dir <path>` - Bot profile directory for credentials

### AutoLogin + ScriptAutoStart

- **AutoLoginPlugin** - Reads `credentials.properties` from `--cc-profile-dir`, injects email/password, calls `LoginManager.setWorld()`
- **ScriptAutoStartPlugin** - Reads `commandcenter.properties` for `script.name`, looks up plugin by `@PluginDescriptor` name via `PluginManager`

## CC Bot Scripts Framework

### CCScript<S extends Enum> (`scripts/core/CCScript.java`)

Generic base class extending Microbot's `Script`:

- **Template methods:** `configure()`, `getInitialState()`, `onTick(State)`
- **Behavior system:** Priority-ordered `CCBehavior` list, checked before `onTick()`
- **State machine:** Generic enum states with timestamp tracking
- **Antiban:** Template setter via `setAntiBanTemplate()`, applied per-script
- **Status reporting:** Script name + running state for Status API

### CCBehavior (`scripts/core/CCBehavior.java`)

Interface for reusable cross-cutting behaviors:

- `priority()` - Lower = higher priority (checked in order)
- `name()` - Display name
- `shouldActivate()` - Guard condition
- `execute()` - Action (returns true if handled tick)
- `reset()` - Called on script stop

### 6 Built-in Behaviors (`scripts/core/behaviors/`)

| Behavior | Priority | Purpose |
|----------|----------|---------|
| DeathRecoveryBehavior | 5 | Teleport/recovery on death |
| EatingBehavior | 10 | Eat when HP% drops below threshold |
| StuckDetectionBehavior | 15 | Detect no-movement timeout, log warning |
| LootingBehavior | 40 | Pick up configured items within radius |
| BuryBonesBehavior | 45 | Bury/scatter bones for prayer XP |
| BankingBehavior | 50 | Bank open/close/withdraw/deposit via BankingConfig |

### 5 Bot Scripts

Each follows the 4-file convention (5 files if it has an enum):

| Script | States | Config Options | Behaviors |
|--------|--------|----------------|-----------|
| CCWoodcutting | CHOPPING, IDLE | tree type, bank logs | Banking, StuckDetection, DeathRecovery |
| CCMining | MINING, IDLE | ore type, bank mode (deposit/drop/manual) | Banking, StuckDetection, DeathRecovery |
| CCFishing | FISHING, IDLE | fish type | Banking, StuckDetection, DeathRecovery |
| CCCooking | COOKING, IDLE | food type, range location | Banking, StuckDetection, DeathRecovery |
| CCCombat | FIGHTING, IDLE | monster name, eat%, loot items, bury bones, progression | Eating, Looting, BuryBones, StuckDetection, DeathRecovery |

All plugins extend `MicrobotPlugin` and use `@PluginDescriptor(name = "CC <Name>")` for ScriptAutoStart lookup.

## Test Framework (119 tests)

```
runelite-client/src/test/java/.../commandcenter/
├── CredentialRedactorTest.java
└── scripts/
    ├── CCScriptTest.java                    # State machine lifecycle (4 tests)
    ├── CCScriptContractTest.java            # Parameterized: 7 tests x 5 scripts = 35
    ├── CCScriptConfigureContractTest.java   # Parameterized: 4 tests x 5 scripts = 20
    ├── core/
    │   ├── CCScriptTestUtils.java           # Reflection: getBehaviors(), hasAntiBanTemplate(), callConfigure(), injectConfig()
    │   ├── StubCCScript.java                # Zero-thread stub for behavior tests
    │   ├── CCBehaviorTestBase.java          # Abstract base: 3 auto-inherited contract tests (priority, name, reset)
    │   └── behaviors/                       # 6 behavior tests (each extends CCBehaviorTestBase)
    └── {woodcutting,mining,fishing,cooking,combat}/  # Per-script tests
```

### Adding a New CC Script

1. Create 4 files: `CC<Name>Config.java`, `CC<Name>Script.java`, `CC<Name>Plugin.java`, `CC<Name>Overlay.java`
2. Add test file extending patterns from existing script tests
3. Add entry to `CCScriptContractTest.SCRIPTS` and `CCScriptConfigureContractTest.SCRIPTS` arrays — gets 11 free contract tests

### Key Test Utilities

- **CCScriptTestUtils.injectConfig()** - Walks class hierarchy to find `@Inject Config` field via reflection
- **CCScriptTestUtils.getBehaviors()** - Accesses private `behaviors` list
- **CCScriptTestUtils.callConfigure()** - Invokes protected `configure()` without modifying production visibility
- **StubCCScript** - Minimal stub with `scheduledExecutorService = Executors.newScheduledThreadPool(0)` to avoid idle threads
- **CCBehaviorTestBase<B>** - Extend this for any behavior test; provides 3 free contract tests

## Queryable API Quick Reference

Prefer the queryable API over legacy `Rs2Npc`/`Rs2Player` util calls:

```java
// NPC query
var banker = Microbot.getRs2NpcCache().query()
    .withName("Banker").nearestOnClientThread();

// Ground item query
var coins = Microbot.getRs2TileItemCache().query()
    .withName("Coins").within(10).firstOnClientThread();
```

Full guide: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/api/QUERYABLE_API.md`

## Interaction & Timing Rules

- Never sleep on the RuneLite client thread; use the script thread with `sleep(...)` / `sleepUntil(...)`
- Never use static sleeps like `sleep(12000)` — always use `sleepUntil(BooleanSupplier)` with optional timeout. Static delays are race-prone; conditional waits self-document the awaited state.
- After interactions, wait for state changes (e.g., `Rs2Bank.isOpen()`, `Rs2Player.isAnimating()`)
- Limit search radius with `.within(...)` to reduce overhead
- Cache query results when reusing in a loop
- `ClientThreadGuardrailTest` (runs in `runUnitTests`) fails when `Rs2*` or query API code calls a client-thread-only RuneLite API outside a `clientThread.invoke*()` lambda

## Microbot CLI (Runtime Agent Control)

`./microbot-cli` is a bash wrapper at repo root; all commands output JSON. Requires a running game client.

```bash
./microbot-cli ct Player.getName          # Client-thread lookup (~10ms, offline)
./microbot-cli ct getItem --like          # Fuzzy match
./microbot-cli login now --world 381 --timeout 60
./microbot-cli keyboard type "text"
./microbot-cli keyboard enter
./microbot-cli widgets click 548 52
./microbot-cli varbit <id>
```

**Client-thread lookup** (`ct` / `client-thread`): reads `docs/client-thread-lookup.tsv`; verdicts: `REQUIRED (asserted)`, `Strongly indicated (N signals)`, `Likely (single signal)`. Absence from manifest does **not** prove a method is safe.

Full reference: `docs/MICROBOT_CLI.md`; HTTP API: `docs/AGENT_SERVER.md`

## Working with In-Game Settings

Always use the settings search bar — never navigate by tab index (tab indices shift on game updates; the "Interfaces" tab was removed).

Flow: open Settings tab (548:52) → click "All Settings" → click Search (134:11) → type setting name → find widget → click it.

```bash
./microbot-cli widgets click 548 52 && sleep 1 && \
./microbot-cli widgets click --text "All Settings" && sleep 1 && \
./microbot-cli widgets click 134 11 && sleep 1 && \
./microbot-cli keyboard type "level-up" && sleep 1 && \
./microbot-cli widgets click --text "Show level only"
```

For dropdowns: invoke `Select` action to open, then `widgets click --text` to pick an option. Always verify with `./microbot-cli varbit <id>`.

Details: `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/settings/CLAUDE.md`

## Entity Gotchas

Per-entity footgun guides live in `docs/entity-guides/`. Each captures real bugs from hidden assumptions at call sites.

**Always check `docs/entity-guides/README.md` first** before implementing or modifying any utility under `microbot/util/<entity>/`.

- [`docs/entity-guides/items.md`](docs/entity-guides/items.md) — read before touching `Rs2Inventory`, `Rs2Bank`, `Rs2Equipment`, `Rs2GroundItem`, `Rs2Shop`, `Rs2DepositBox`, or any helper that maps item names to a hardcoded menu action.

When you fix a bug whose root cause was a hidden entity assumption, add a numbered gotcha to the relevant guide so future agents don't repeat it.

## Security Posture

- **Telemetry removed:** `MicrobotVersionChecker`, `RandomFactClient`, `MicrobotApi` (→ `microbot.cloud`) deleted
- **CredentialRedactor** strips sensitive data from logs
- **Localhost-only** Status API (no remote exposure)
- **RuneLite core telemetry** gated by `--disable-telemetry` flag (not removed — would break upstream sync)
- Full audit: `docs/security-audit.md`

## Key References

- Architecture decisions: `docs/decisions/` (4 ADRs)
- Development setup: `docs/development.md`
- Security audit: `docs/security-audit.md`
- Upstream sync guide: `docs/upstream-sync.md`
- API reference: `docs/api/` (30+ utility docs)
- Example scripts: `runelite-client/src/main/java/.../microbot/example/`
- Full Microbot plugin guide (threading, Rs2* utilities, queryable API, script lifecycle): `runelite-client/src/main/java/net/runelite/client/plugins/microbot/CLAUDE.md`
- Agentic testing loop: `docs/AGENTIC_TESTING_LOOP.md`
