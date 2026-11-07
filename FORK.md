# Arcane Academy fork of MineClient Bridge

This is Carter's fork of [Campione01/MineClient-Bridge](https://github.com/Campione01/MineClient-Bridge) (MIT), used to let local coding agents drive the Arcane Academy development clients. It adds what those in-game checklists needed; see the [changelogs](docs/) whose names end in `-aa.N`.

## Branches and versions

| Branch or tag | What it is |
|---|---|
| `main` | Mirror of upstream `main`. Never commit here. |
| `arcane` | The fork's line: an upstream release tag plus our changes. Work and release from here. |
| `vX.Y.Z-aa.N` | Fork releases. `X.Y.Z` is the upstream release the fork is based on; `N` counts fork releases on top of it. |

Fork changes are marked `[Arcane Academy fork]` in code comments. New behavior goes in new files where practical (`TickClock.java`, `ChatSnapshot.java`, `mcp/fork-extensions-test.mjs`), so upstream updates merge with few conflicts.

## Working on the fork

The clone lives at `C:\dev\MineClient-Bridge`, on branch `arcane`, with `origin` = this fork and `upstream` = Campione01.

- **MCP server changes** take effect as soon as the agent restarts the MCP server, because Claude Code and Codex run `mcp\mineclient-bridge-mcp.mjs` straight from this clone. In Claude Code, run `/mcp` and reconnect `mineclient-bridge`; restart Codex. Test with `npm --prefix mcp test`.
- **Mod changes** need a build, then a dev client launched with the local jar. In this clone:

  ```bat
  gradlew.bat build
  ```

  Then in the wizard-modpack repository:

  ```bat
  gradlew.bat runClient -PbridgeIsolatedInput=true -PbridgeJar=C:\dev\MineClient-Bridge\build\libs\mineclient-bridge-neoforge-1.21.1-1.1.8-aa.1.jar
  ```

  `-PbridgeJar` replaces the pinned release for that launch only. Use the jar name your build produced.

## Releasing

1. Write `docs\changelog-<version>.md`.
2. Commit your changes on `arcane`.
3. Run the release script. It bumps the version in the five places it is written, builds, runs every test, commits, tags, and pushes:

   ```bat
   powershell -ExecutionPolicy Bypass -File scripts\release.ps1 -Version 1.1.8-aa.2
   ```

   Add `-NoPush` to stop before anything leaves the PC.
4. The pushed tag runs `.github/workflows/release.yml`, which builds and tests again on GitHub and publishes the jar as a release asset. Watch it with `gh run watch --repo DeveloperCarter/MineClient-Bridge`.
5. In wizard-modpack, set `mineclient_bridge_version` in `gradle.properties` to the new version.

Keep the clone checked out on `arcane` at the released version (or newer work), so the MCP server the agents run matches the mod in the clients.

## Taking an upstream release

```bat
git fetch upstream --tags
git switch arcane
git merge vX.Y.Z
```

Resolve conflicts (most likely in `BridgeServer.java`, the tests that pin upstream's tool list, and the version strings), then release as `vX.Y.Z-aa.1`.

## Contributing back

The scroll path and the MCP additions are useful to anyone. If Carter decides to offer them upstream, open pull requests from topic branches based on upstream `main`, one change per pull request.
