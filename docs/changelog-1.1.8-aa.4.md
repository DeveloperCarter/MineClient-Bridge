# MineClient Bridge 1.1.8-aa.4 (Arcane Academy fork)

Two journal gaps closed: client-command feedback, and how loud looping sounds actually are. Built on branch `observability-2` from released `1.1.8-aa.3` (`7175bcf`), 13 November 2026. Released with Carter's approval after wizard-modpack's T074 combined verification. See [FORK.md](../FORK.md).

The parked focus-hint experiment (local `parked/focus-hints`) is still **not** included.

## Client-command feedback in the journal

- Feedback that a client command adds to chat directly, bypassing NeoForge's receive-chat event (for example `/arcaneacademy_probe`), now reaches the journal as `client_feedback` events. Normal received chat keeps its own event types.
- It reuses the existing read-only chat access, with identity-based, bounded deduplication. Identical replies sent again stay separate events.
- Text uses the journal's existing size bound, with a truncation marker. Full mod state stays available through probes.

## Looping sounds

- New query kind `sounds`, served by authenticated `GET /control/sounds` on the client thread, lists active loops. Each row has a stable process-local instance ID, the source, the resolved instance volume and pitch, the source and master volume, and `effective_volume`: the gain before spatial attenuation, with the source and master sliders included.
- `looping_sound` journal events carry `start`, `sample` and `stop` phases. Changes are published at most every four client ticks, and an unchanged heartbeat every twenty.
- At most 64 loops are tracked, and references are cleared on stop and disconnect. A loop whose audio hasn't started gets twenty ticks before it counts as inactive.
- No mixins and no new access transformers.

## Verification

- **Windows 11:** `.\gradlew.bat build` passes 85 Java tests. The control-surface contract now pins 21 authenticated endpoints, including GET `/control/sounds`. `npm --prefix mcp test` passes the framing, ZIP64, self-test and fork suites, including the schema, forwarding and error tests for both additions.
- **Live, with wizard-modpack's development tools:**
  - Client feedback: 11 of 11 steps.
  - Looping sound volume and stop: 26 of 26 steps. The netherite runeblade hum reported volume 0.072 at pitch 0.55, and nominal gain 0.072 → 0.036 → 0.072 as the Players slider went 1 → 0.5 → 1. Dismissal produced a stop event and an empty loop list.
- **Combined verification, wizard-modpack T074 on `f01d94a`:**
  - both scenarios passed again on a clean launch of this jar;
  - the dedicated two-player fixture passed with it.
