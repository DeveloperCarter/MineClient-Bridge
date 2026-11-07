# MineClient Bridge 1.1.8-aa.1 (Arcane Academy fork)

First release of the Arcane Academy fork, based on upstream 1.1.8. See [FORK.md](../FORK.md).

## Mod

- **Scroll follows the physical wheel's path.** World scroll now goes through `MouseHandler.onScroll`, the method a real mouse wheel drives, instead of calling the NeoForge scroll hook and then `Inventory.swapPaint` directly. Vanilla's scroll sensitivity, discrete-scroll option, partial-scroll accumulation, and spectator handling (fly speed or the spectator menu instead of the hotbar) now apply. The NeoForge scroll event still fires. Scroll responses add `selected_slot_before`, `selected_slot_after`, and `spectator`.
- **`settle_ticks` on every input request** (`/control/key`, `/control/raw-key`, `/control/look`, `/control/mouse`, `/control/text`, `/control/command`): 0 to 40. After applying the input, the response waits until that many client ticks have finished, and reports `client_tick` and `settled`. Inputs sent faster than the game ticks no longer get consumed by the same tick.
- **`GET /control/chat?limit=N`** (1 to 100, default 20): recent chat messages, newest first, with their tick and tag, plus the action-bar message and its remaining ticks. Read-only.
- Capabilities list `/control/chat`, `settle_ticks_max`, `chat_messages_max`, and `tick_clock`.

## MCP server

- **`minecraft_client_attach`**: register a running client from its game folder and port. Reads the token from `<runtime_root>\config\mineclient-bridge.token` and the identity from the bridge's status, then runs the same checks as `minecraft_client_register`. Idempotent for a client that is already registered and running.
- **Exited registrations are replaced.** Registering or attaching first removes registrations whose client process has exited, instead of refusing until `minecraft_client_close` is called.
- **`minecraft_client_frame` can save to the evidence folder**: `save_as` writes `<evidence_root>\<name>.png` (no overwrite unless `overwrite: true`); `include_image: false` returns only the path.
- **`minecraft_client_sequence`**: up to 100 input, `wait`, `frame`, and `query` steps in one call. Every step is validated before any runs; identity and input isolation are checked before each step; stops at the first failure unless `continue_on_error`. `default_settle_ticks` applies to input steps.
- **`minecraft_client_query` kind `chat`** with optional `limit`.
- **`settle_ticks`** on `minecraft_client_input`.
- Bridge HTTP errors now include the bridge's error code, for example `Bridge HTTP 409 for /control/command (not_in_world)`.
