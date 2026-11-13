# MineClient Bridge 1.1.8-aa.3 (Arcane Academy fork)

Text-first observation for agents: read what happened and what a mod knows as JSON, wait for conditions instead of guessing, and spend fewer screenshots. Built on branch `observability` from released `1.1.8-aa.2`, 12 November 2026. Not released yet: release needs Carter's go-ahead. See [FORK.md](../FORK.md) and the wizard-modpack plan `docs/tools/bridge-observability-plan.md`.

The parked focus-hint experiment (local `cf07a90`, `cb37928`, whose notes used this version number) is **not** included. If it is ever revived it takes a later version.

## Event journal

- `GET /control/journal?since=&types=&limit=`, MCP query kind `journal`. A bounded ring buffer (2048 events, read at most 500 at a time) of chat, system chat, action-bar lines, screen open and close (class and title), sounds started (ID, source, volume, pitch, position, looping), and the bridge's own POST inputs. Each event has a sequence number, game tick and time. Pass the returned `next` as `since`; a cursor past the end returns the newest sequence without events. Overflow drops the oldest and reports `missed` and `dropped_total`.
- NeoForge events only (`ClientChatReceivedEvent`, `ScreenEvent.Opening/Closing`, `PlaySoundEvent`); no mixins. Client-command feedback is added to chat directly by NeoForge and does not appear.

## Development-mod link

- Through InterModComms, a mod may send `probe_reader` (`Function<String, String>`: probe name to JSON) and `journal_source` (`Consumer<BiConsumer<String, String>>`: called once with a sink for events). No compile dependency in either direction.
- `GET /control/probe?name=`, MCP query kind `probe`. The reader runs on the HTTP worker thread; the mod moves the read to the right game thread. `capabilities` reports `mod_probes`.

## Particles and frames

- `GET /control/particles`, MCP query kind `particles`: live particles counted by class, plus the particle option. One read-only access transformer line opens `ParticleEngine.particles`.
- `/control/frame` takes `scale` (0.1-1) and a GUI-scaled region `x, y, w, h`. The `X-MineClient-Frame` header (shown by the MCP as `frame`) gives source and output size, GUI scale, and `changed_fraction` and `changed_region` against the previous frame, from a 64×36 grey thumbnail.

## MCP server

- New tool `minecraft_client_wait_for` and sequence step `{kind:"wait_for"}`: poll until a condition holds (`screen`, `chat` pattern, `journal` event, `entity` nearby, `state` path, `probe` field) with operators `equals`, `not_equals`, `lt`, `gt`, `lte`, `gte`, `matches`, `exists`, `absent`. Timeout at most 30 s, interval 50-2000 ms, optional `since` cursor for chat and journal. A timeout fails with the last value seen.
- Every tool takes an optional `check` label; each call appends one line to `<evidence_root>\telemetry.jsonl` (tool, check, duration, ok, steps, frames, images returned, image bytes, reply characters). Telemetry never fails a call.
- Tool replies are compact JSON instead of indented.

## Verification

- Windows 11: `.\gradlew.bat build` (Java tests including `EventJournalTest`; the contract test now expects 20 control endpoints, the three new ones GET-only). `npm --prefix mcp test`: framing, upstream self-test and fork tests, which cover the journal cursor, probe query, wait_for (probe, chat after start, screen, timeout, sequence step), frame scale and region, and telemetry.
- Live, with wizard-modpack's development probes: an isolated client loaded this jar with `-PbridgeJar`; the mod's probe reader and journal source linked; a 22-step scenario (probes, server command replies, inputs, chat, journal and probe waits, a prevented fall, particles, a scaled frame) and a 9-step journal check (inventory screen events, a played sound, scaled and cropped frames) passed.
