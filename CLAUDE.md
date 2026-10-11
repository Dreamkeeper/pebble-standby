# Standby — instructions for coding agents

Standby is a wearable safety monitor: a Pebble Time 2 watchapp (C, worker +
app), an Android companion (Kotlin, Jetpack Compose, Material 3) and a
self-hosted FastAPI server. Settled decisions: humans are alerted first and
nothing calls emergency services by itself; the server is self-hosted; any
LLM is advisory only; machine identifiers (`org.cryomonitor`) stay as they
are. This file is the map. Read the file it points to before working in
that area; do not work from memory of "how such apps usually look".

## Where things are

| Area | Code | Read first |
|---|---|---|
| Watch detectors and ladder | `watchapp/src/core/` (pure C, host-tested) | `openspec/specs/detector-ladder/spec.md`, `openspec/specs/suspension/spec.md` |
| Watch shell | `watchapp/src/c/main.c`, `watchapp/worker_src/c/worker.c` | `watchapp/README.md` |
| Phone ↔ watch protocol | `watchapp/src/core/protocol.h`, `android/.../Protocol.kt` | `openspec/specs/watch-phone-protocol/spec.md` |
| Companion UI | `android/app/src/main/java/org/cryomonitor/companion/ui/` | **`docs/DESIGN.md`** (all of §3 and §4) |
| Companion service | `android/.../MonitorService.kt`, `CoverageState.kt`, `WearerWords.kt` | `openspec/specs/companion-resilience/spec.md` |
| Server | `server/app/` | `openspec/specs/escalation-and-deadman/spec.md`, `docs/DEPLOY-SYNOLOGY.md` |

Work is driven through OpenSpec changes (`openspec/changes/<name>/` with
proposal, tasks and spec deltas; `/opsx:propose`, `/opsx:apply`,
`/opsx:archive`). A behaviour change without a spec delta is incomplete.

## UI work (the companion)

1. Open `docs/DESIGN.md` first. §3.1 names the six coverage states and
   their colours, §3.2 the words a wearer sees, §3.5 which control carries
   which action, §4 the screen inventory, §9 the acceptance checks.
2. Emphasis follows the action, not the screen (§3.5): one filled button per
   screen, for the action that completes its purpose (the verdict's action,
   Save, Enrol), full width and 56 dp when tapped in a hurry; a filled tonal
   button for a deliberate secondary act that starts its own flow (Pause);
   outlined for an alternative beside a primary; text buttons only inside
   lists, rows and sheets. A text button is never the only way to start
   something.
3. State on the row, features first (§4.5): a settings row states its
   current value; nothing describes itself; no "wall of text".
4. Wearer words only. `WearerWordsAuditTest` fails the build on engineering
   jargon in `strings.xml` and `WearerWords.kt` (worker, heartbeat,
   escalation, nonmotion, check-in as one word, and so on) and on medical
   claims. Say what happens to the wearer, never how the code did it.
5. Reuse `CmTheme` tokens and the existing composables (`SubScreen`,
   `ValueRow`, `SectionHeader`, `InfoCard`, `VerdictCard`). Ask before
   adding a colour, a radius or a new component kind, and record an approved
   change in `docs/DESIGN.md` in the same commit.
6. Before a preview goes to the owner, run the design critique on the
   screens you touched (the `design:design-critique` skill when available,
   otherwise walk §9 by hand) and fix what it finds.

## Build and verify

- Watch host tests and build (WSL, SDK 4.33.1):
  `cd watchapp/tests && gcc -Wall -Wextra -std=c11 -I../src/core -o /tmp/test_runner test_detectors.c ../src/core/detectors.c && /tmp/test_runner`
  then `cd watchapp && pebble build` → `build/watchapp.pbw` → `dist/standby-v<version>.pbw`.
- Companion: `gradle --no-daemon -q testSideloadDebugUnitTest assembleSideloadRelease`
  (JDK 17) → `dist/standby-companion-v<version>-<tag>-release.apk`. Bump
  `versionCode` for every APK that leaves the machine.
- Server: `cd server && .venv/Scripts/python.exe -m pytest -q`. Deploy per
  `docs/DEPLOY-SYNOLOGY.md`; mount the compose directory at its own path.
- Field evidence beats reasoning: when behaviour is in doubt, ask the owner
  for a diagnostics bundle (they send it; never pull) and read the minute
  records with `tools/worker_log_timeline.py`.

## Do not

- Commit secrets (`tokens.txt`, keystores, the Synology key) or
  `docs/OUTREACH-DRAFTS.md`.
- Install to a device over ADB without checking `adb devices -l` names the
  phone; a Meta Quest is often the only device listed.
- Add a setting for something the app can observe. Prefer removing a
  choice to adding one.
- Push PebbleOS or Core-app branches, or post upstream, without the owner
  saying so.
