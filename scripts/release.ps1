# [Arcane Academy fork] Cut a fork release: bump the version everywhere it is written, build and
# test, commit, tag, and push. Pushing the tag starts .github/workflows/release.yml, which builds
# again on GitHub and publishes the jar as a release asset.
#
# Usage, from anywhere:
#   powershell -ExecutionPolicy Bypass -File C:\dev\MineClient-Bridge\scripts\release.ps1 -Version 1.1.8-aa.2
#   ... -NoPush   to stop after the local commit and tag (nothing leaves the PC)
#
# Write docs\changelog-<version>.md first; the release notes come from it.

param(
    [Parameter(Mandatory = $true)][string]$Version,
    [switch]$NoPush
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

function Invoke-Checked([string]$Description, [scriptblock]$Command) {
    Write-Host "== $Description"
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Description failed (exit $LASTEXITCODE)" }
}

if ($Version -notmatch '^\d+\.\d+\.\d+-aa\.\d+$') { throw "Version must look like 1.1.8-aa.2" }
if (git status --porcelain) { throw "The working tree has uncommitted changes; commit or stash them first." }
$branch = git branch --show-current
if ($branch -ne 'arcane') { throw "Release from the arcane branch (currently on '$branch')." }
if (git tag --list "v$Version") { throw "Tag v$Version already exists." }
if (-not (Test-Path "docs/changelog-$Version.md")) { throw "Write docs/changelog-$Version.md first." }

$current = @((Get-Content gradle.properties) | Where-Object { $_ -match '^mod_version=' })[0].Split('=', 2)[1].Trim()
if ($current -eq $Version) { throw "mod_version is already $Version." }

# Every place the version is written, as exact before/after strings, so nothing else changes.
$replacements = @(
    @{ File = 'gradle.properties';              Old = "mod_version=$current";                          New = "mod_version=$Version" },
    @{ File = 'mcp/package.json';               Old = "`"version`": `"$current`"";                     New = "`"version`": `"$Version`"" },
    @{ File = 'mcp/mineclient-bridge-mcp.mjs';  Old = "SERVER_VERSION = `"$current`"";                 New = "SERVER_VERSION = `"$Version`"" },
    @{ File = 'mcp/self-test.mjs';              Old = "serverInfo.version, `"$current`"";              New = "serverInfo.version, `"$Version`"" },
    @{ File = 'mcp/ndjson-framing-test.mjs';    Old = "version: `"$current`" }";                       New = "version: `"$Version`" }" }
)
$utf8 = New-Object System.Text.UTF8Encoding($false)
foreach ($r in $replacements) {
    $path = Join-Path (Get-Location) $r.File
    $text = [IO.File]::ReadAllText($path)
    if (-not $text.Contains($r.Old)) { throw "$($r.File) does not contain '$($r.Old)'" }
    [IO.File]::WriteAllText($path, $text.Replace($r.Old, $r.New), $utf8)
}

Invoke-Checked 'Build and test the mod' { .\gradlew.bat clean build --console=plain }
Invoke-Checked 'Test the MCP server' { npm --prefix mcp test }

$jar = "build/libs/mineclient-bridge-neoforge-1.21.1-$Version.jar"
if (-not (Test-Path $jar)) { throw "Expected $jar after the build." }

Invoke-Checked 'Commit' { git add -A; git commit -m "Release $Version" }
Invoke-Checked 'Tag' { git tag -a "v$Version" -m "MineClient Bridge $Version (Arcane Academy fork)" }

if ($NoPush) {
    Write-Host "Committed and tagged v$Version locally. Push later with: git push origin arcane; git push origin v$Version"
    return
}
Invoke-Checked 'Push branch' { git push origin arcane }
Invoke-Checked 'Push tag' { git push origin "v$Version" }

Write-Host ""
Write-Host "Pushed v$Version. GitHub Actions now publishes the release:"
Write-Host "  gh run watch --repo DeveloperCarter/MineClient-Bridge"
Write-Host "Then, in the wizard-modpack repository, set mineclient_bridge_version=$Version in gradle.properties."
