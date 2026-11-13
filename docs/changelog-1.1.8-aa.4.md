# 1.1.8-aa.4 candidate

Unreleased, 13 November 2026. T073 on observability-2. Current local artifacts still identify as aa.3 until Carter approves release.

- Local command feedback that bypasses NeoForge's receive-chat event reaches the journal as client_feedback. Polling uses the existing read-only chat AT and identity-based bounded deduplication. Normal received chat keeps its original event type. Identical repeated local replies remain separate events.
- Active loops appear in query kind sounds, through authenticated GET /control/sounds on the client thread. Each row includes a stable process-local instance ID, source, resolved instance volume/pitch, source/master volume and effective_volume. The last value is nominal pre-distance gain, including source/master sliders. It describes the source gain before spatial attenuation.
- looping_sound journal events carry start, sample and stop phases. Changes publish at most every four client ticks and an unchanged heartbeat every twenty. Track at most64 loops and clear references on stop/disconnect. Pending audio startup gets twenty ticks before being considered inactive. No mixins or additional access transformers are needed.
- Client feedback uses the journal's existing bounded text size and includes a truncation marker. Full mod state remains available through probes.
- MCP schema/forwarding/error tests and Java tests cover both additions. Exact Windows verification and live report paths are recorded after local testing. No release, tag or pin change is authorized here.


## Local verification

Windows11 PowerShell, observability-2 from7175bcf. `npm --prefix mcp test` passes the eight Node tests, ZIP64 cases and the MCP self-test suites. `gradlew.bat build` passes85 Java tests. The control-surface contract now includes21 authenticated endpoints, explicitly checking GET /control/sounds.

Wizard worktree C:\dev\wizard-modpack-building launched an isolated Quick Play client with the local aa.3-named jar. The placement log names PID31156 placed on the second monitor. Saved scenarios verify repeated client.player command replies and no duplicate server marker. Report bridge-client-feedback-2026-11-13T08-30-51-610Z.json. Netherite hum reports resolved volume0.072, pitch0.55, Players volume1→0.5→1 and nominal gain0.072→0.036→0.072. Dismissal produces a stop event and an empty active-loop query. Main report bridge-looping-sound-volume-2026-11-13T08-37-39-719Z.json, under that worktree's run/bridge-evidence/scenarios. No frames/images were taken for T073. The slider metadata gap is on the bridge tooling board. The volume scenario uses verified coordinates guarded by854x480 GUI assertions. All owned clients closed through the bridge. Final combined delivery and release approval remain T074/Carter.
