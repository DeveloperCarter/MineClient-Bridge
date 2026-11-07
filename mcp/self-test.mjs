#!/usr/bin/env node

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import fsSync from "node:fs";
import fs from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import vm from "node:vm";
import { fileURLToPath } from "node:url";
import { deflateRawSync, inflateRawSync } from "node:zlib";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER_FILE = path.join(HERE, "mineclient-bridge-mcp.mjs");
const PREPARED_PARENT = path.win32.join(
  fsSync.realpathSync.native(os.tmpdir()),
  "mineclient-bridge-self-test-runs"
);
const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64"
);
const INPUT_ISOLATION = {
  enabled: true,
  mode: "process_local_virtual",
  suppressed_cursor_operations: 0,
  native_callback_registration_blocks: 2
};
let lastClientStderr = "";

if (process.argv[2] === "--fixture-bridge") {
  await runFixtureBridge(process.argv[3]);
} else if (process.argv[2] === "--zip64-directory-only") {
  await runZip64DirectoryTests();
} else {
  await runSelfTest();
}

async function runSelfTest() {
  await runZip64DirectoryTests();
  const suffix = `${process.pid}-${Date.now().toString(36)}`;
  const namespace = `self-test-${suffix}`;
  const launchRun = `mineclient-launch-${suffix}`;
  const invalidLaunchRun = `mineclient-invalid-${suffix}`;
  const manualRun = `mineclient-register-${suffix}`;
  const exitRun = `mineclient-exit-${suffix}`;
  const overlapRun = `mineclient-overlap-${suffix}`;
  const launchRoot = path.join(PREPARED_PARENT, launchRun);
  const invalidLaunchRoot = path.join(PREPARED_PARENT, invalidLaunchRun);
  const manualRoot = path.join(PREPARED_PARENT, `mineclient-manual-${suffix}`);
  const stateRoot = path.join(HERE, namespace);
  const manualToken = `manual-token-${suffix}`;
  const createdRoots = [launchRoot, invalidLaunchRoot, manualRoot];

  let client;
  let manualBridge;
  let fixturePid = null;
  const allToolResponses = [];

  try {
    await fs.mkdir(PREPARED_PARENT, { recursive: true });
    await createPreparedFixture(launchRoot, launchRun, `FixtureDesktop-${launchRun}`, false);
    await createPreparedFixture(invalidLaunchRoot, invalidLaunchRun, `FixtureDesktop-${invalidLaunchRun}`, true);

    const manualRuntime = path.join(manualRoot, "runtime");
    const manualEvidence = path.join(manualRoot, "evidence");
    const rejectRuntime = path.join(manualRoot, "reject-runtime");
    const rejectEvidence = path.join(manualRoot, "reject-evidence");
    const exitRuntime = path.join(manualRoot, "exit-runtime");
    const exitEvidence = path.join(manualRoot, "exit-evidence");
    await Promise.all([
      fs.mkdir(manualRuntime, { recursive: true }),
      fs.mkdir(manualEvidence, { recursive: true }),
      fs.mkdir(rejectRuntime, { recursive: true }),
      fs.mkdir(rejectEvidence, { recursive: true }),
      fs.mkdir(exitRuntime, { recursive: true }),
      fs.mkdir(exitEvidence, { recursive: true })
    ]);

    const manualIdentity = {
      run_id: manualRun,
      process_id: process.pid,
      desktop_name: `ManualDesktop-${manualRun}`,
      runtime_root: manualRuntime,
      evidence_root: manualEvidence
    };
    manualBridge = await createBridgeServer({
      identity: manualIdentity,
      token: manualToken,
      closeOnRequest: false
    });

    client = createMcpClient({
      NODE_ENV: "test",
      MINECLIENT_BRIDGE_TEST_NAMESPACE: namespace,
      MINECLIENT_BRIDGE_PREPARED_ROOT_PARENT: PREPARED_PARENT,
      MINECLIENT_BRIDGE_TEST_LAUNCH_TIMEOUT_MS: "15000",
      MINECLIENT_BRIDGE_TEST_POLL_INTERVAL_MS: "50"
    });

    const initialized = await client.request("initialize", {
      protocolVersion: "2024-11-05",
      capabilities: {},
      clientInfo: { name: "mineclient-bridge-self-test", version: "1.1.8" }
    });
    assert.equal(initialized.result.serverInfo.name, "mineclient-bridge");
    assert.equal(initialized.result.serverInfo.version, "1.1.8-aa.1");

    const listed = await client.request("tools/list", {});
    const toolNames = listed.result.tools.map((tool) => tool.name);
    assert.deepEqual(toolNames, [
      "minecraft_client_launch",
      "minecraft_client_register",
      "minecraft_client_attach",
      "minecraft_client_status",
      "minecraft_client_frame",
      "minecraft_client_query",
      "minecraft_client_input",
      "minecraft_client_sequence",
      "minecraft_client_close",
      "minecraft_client_reload_resources"
    ]);
    for (const tool of listed.result.tools) {
      assert.equal(tool.inputSchema.type, "object", `${tool.name} must declare an object input schema`);
      for (const combinator of ["oneOf", "anyOf", "allOf"]) {
        assert.equal(tool.inputSchema[combinator], undefined, `${tool.name} must not use a top-level ${combinator}`);
      }
    }
    const inputTool = listed.result.tools.find((tool) => tool.name === "minecraft_client_input");
    assert.deepEqual([...inputTool.inputSchema.properties.kind.enum].sort(), [
      "command",
      "key",
      "look",
      "mouse",
      "raw_key",
      "release_all",
      "text"
    ]);
    assert.deepEqual(inputTool.inputSchema.required, ["run_id", "kind"]);
    assert.equal(inputTool.inputSchema.properties.command.maxLength, 512);
    const queryTool = listed.result.tools.find((tool) => tool.name === "minecraft_client_query");
    assert.deepEqual([...queryTool.inputSchema.properties.kind.enum].sort(), [
      "capabilities",
      "chat",
      "keymaps",
      "screen",
      "state"
    ]);

    const commonReject = {
      run_id: `mineclient-reject-${suffix}`,
      token: manualToken,
      process_id: process.pid,
      desktop_name: `RejectDesktop-${suffix}`,
      runtime_root: rejectRuntime,
      evidence_root: rejectEvidence
    };
    await expectToolError(client, "minecraft_client_register", {
      ...commonReject,
      base_url: "http://192.0.2.10:34567"
    }, /loopback/);

    await expectToolError(client, "minecraft_client_register", {
      ...manualIdentity,
      base_url: manualBridge.baseUrl,
      token: manualToken,
      process_id: process.pid + 1
    }, /process_id/);

    await expectToolError(client, "minecraft_client_register", {
      ...commonReject,
      base_url: manualBridge.baseUrl,
      process_id: process.pid
    }, /run_id/);

    const manualDescriptor = {
      ...manualIdentity,
      base_url: manualBridge.baseUrl,
      token: manualToken
    };
    const registeredResult = await client.callTool("minecraft_client_register", manualDescriptor);
    allToolResponses.push(registeredResult);
    const registered = textPayload(registeredResult);
    assert.equal(registered.registered, true);
    assert.equal(registered.session.run_id, manualRun);
    assert.equal(Object.hasOwn(registered.session, "token"), false);
    assert.equal(JSON.stringify(registeredResult).includes(manualToken), false);

    const manualDescriptorFile = path.join(stateRoot, `${manualRun}.json`);
    const storedManual = JSON.parse(await fs.readFile(manualDescriptorFile, "utf8"));
    assert.deepEqual(storedManual, manualDescriptor);

    const statusResult = await client.callTool("minecraft_client_status", { run_id: manualRun });
    allToolResponses.push(statusResult);
    const status = textPayload(statusResult);
    assert.equal(status.bridge.run_id, manualRun);
    assert.equal(status.bridge.token_echo, "[redacted]");
    assert.equal(JSON.stringify(statusResult).includes(manualToken), false);

    const frameResult = await client.callTool("minecraft_client_frame", { run_id: manualRun });
    allToolResponses.push(frameResult);
    assert.equal(frameResult.isError, false);
    const image = frameResult.content.find((block) => block.type === "image");
    const identityText = frameResult.content.find((block) => block.type === "text");
    assert.equal(image.mimeType, "image/png");
    assert.deepEqual(Buffer.from(image.data, "base64"), PNG);
    assert.match(identityText.text, new RegExp(`run_id=${manualRun}`));

    for (const isolation of [undefined, { ...INPUT_ISOLATION, enabled: false },
      { ...INPUT_ISOLATION, mode: "native" }, { ...INPUT_ISOLATION, enabled: "true" },
      { enabled: true, mode: "process_local_virtual" },
      ...[0, 1, 2.5, "2"].map((count) => ({ ...INPUT_ISOLATION, native_callback_registration_blocks: count }))]) {
      manualBridge.setInputIsolation(isolation);
      const start = manualBridge.requests.length;
      for (const input of [{ kind: "key", mapping: "key.forward", action: "press" },
        { kind: "mouse", action: "click", button: 0 }]) {
        await expectToolError(client, "minecraft_client_input", { run_id: manualRun, ...input }, /input isolation/);
      }
      assert.equal(manualBridge.requests.slice(start).some((entry) => entry.method === "POST"), false,
        "blocked isolation must not dispatch key/mouse input");
      for (const name of ["minecraft_client_status", "minecraft_client_frame"]) {
        assert.equal((await client.callTool(name, { run_id: manualRun })).isError, false);
      }
      assert.equal((await client.callTool("minecraft_client_input", {
        run_id: manualRun, kind: "release_all"
      })).isError, false);
    }
    manualBridge.setInputIsolation(INPUT_ISOLATION);
    assert.equal((await client.callTool("minecraft_client_input", {
      run_id: manualRun, kind: "key", mapping: "key.forward", action: "tap"
    })).isError, false);
    assert(manualBridge.requests.some((entry) => entry.path === "/control/key"));

    await expectToolError(client, "minecraft_client_register", {
      run_id: overlapRun,
      base_url: "http://127.0.0.1:65534",
      token: manualToken,
      process_id: process.pid + 10,
      desktop_name: `OverlapDesktop-${suffix}`,
      runtime_root: manualRuntime,
      evidence_root: manualEvidence
    }, /roots overlap/);

    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: manualRun,
      kind: "release_all"
    }));
    await fs.unlink(manualDescriptorFile);

    await expectToolError(client, "minecraft_client_launch", {
      run_id: invalidLaunchRun,
      prepared_root: invalidLaunchRoot
    }, /final-preflight/);
    assert.equal(
      await exists(path.join(invalidLaunchRoot, "game", "config", "mineclient-bridge.json")),
      false
    );

    for (const variant of ["old", "missing-mixin", "bad-config", "duplicate"]) {
      const runId = `mineclient-jar-${variant}-${suffix}`;
      const root = path.join(PREPARED_PARENT, runId);
      createdRoots.push(root);
      await createPreparedFixture(root, runId, `FixtureDesktop-${runId}`, false);
      await fs.writeFile(path.join(root, "game", "mods", "renamed-client.jar"), fixtureJar(variant));
      if (variant === "duplicate") {
        await fs.writeFile(path.join(root, "game", "mods", "second-name.jar"), fixtureJar());
      }
      await expectToolError(client, "minecraft_client_launch", { run_id: runId, prepared_root: root }, /input isolation/i);
      assert.equal(await exists(path.join(root, "fixture-launch-started.txt")), false);
      assert.equal(await exists(path.join(root, "game", "config", "mineclient-bridge.json")), false);
    }

    for (const [label, isolation] of [["missing", null], ["disabled", { ...INPUT_ISOLATION, enabled: false }],
      ["wrong-mode", { ...INPUT_ISOLATION, mode: "native" }],
      ["missing-registration", { enabled: true, mode: "process_local_virtual" }],
      ...[0, 1].map((count) => [`registration-${count}`, { ...INPUT_ISOLATION, native_callback_registration_blocks: count }])]) {
      const runId = `mineclient-unsafe-${label}-${suffix}`;
      const root = path.join(PREPARED_PARENT, runId);
      createdRoots.push(root);
      await createPreparedFixture(root, runId, `FixtureDesktop-${runId}`, false);
      await fs.writeFile(path.join(root, "fixture-input-isolation.json"), JSON.stringify(isolation));
      await expectToolError(client, "minecraft_client_launch", { run_id: runId, prepared_root: root }, /input isolation/);
      const pid = Number.parseInt(await fs.readFile(path.join(root, "fixture-child-pid.txt"), "utf8"), 10);
      assert.equal(await processIsAlive(pid), false, "unsafe launched bridge must exit before returning");
      assert.equal(await exists(path.join(stateRoot, `${runId}.json`)), false);
      assert.equal(await exists(path.join(root, "game", "captures", "fixture-closed.marker")), true);
      const requests = (await fs.readFile(path.join(root, "game", "captures", "fixture-requests.jsonl"), "utf8"))
        .trim().split("\n").map((line) => JSON.parse(line));
      assert(requests.some((entry) => entry.path === "/control/release-all"));
      assert(requests.some((entry) => entry.path === "/control/close"));
      assert.equal(requests.some((entry) => ["/control/key", "/control/mouse"].includes(entry.path)), false);
    }

    const launchResult = await client.callTool(
      "minecraft_client_launch",
      { run_id: launchRun, prepared_root: launchRoot },
      20_000
    );
    allToolResponses.push(launchResult);
    if (launchResult.isError) {
      const diagnostics = {};
      for (const name of [
        "fixture-launch-started.txt",
        "fixture-launch-error.txt",
        "fixture-child-pid.txt",
        "fixture-stdout.log",
        "fixture-stderr.log"
      ]) {
        const file = path.join(launchRoot, name);
        if (await exists(file)) {
          diagnostics[name] = await fs.readFile(file, "utf8");
        }
      }
      throw new Error(
        `Launch fixture failed: ${launchResult.content[0]?.text ?? "unknown"} diagnostics=${JSON.stringify(diagnostics)}`
      );
    }
    const launched = textPayload(launchResult);
    assert.equal(launched.readiness, "ready");
    assert.equal(launched.run_id, launchRun);
    assert.equal(launched.desktop_name, `FixtureDesktop-${launchRun}`);
    assert.equal(launched.runtime_root, path.join(launchRoot, "game"));
    assert.equal(launched.evidence_root, path.join(launchRoot, "game", "captures"));

    const bridgeConfigPath = path.join(launchRoot, "game", "config", "mineclient-bridge.json");
    const bridgeConfig = JSON.parse(await fs.readFile(bridgeConfigPath, "utf8"));
    const bridgeTokenPath = path.join(launchRoot, "game", "config", "mineclient-bridge.token");
    const bridgeToken = await fs.readFile(bridgeTokenPath, "utf8");
    assert.equal(bridgeConfig.enabled, true);
    assert.equal(bridgeConfig.host, "127.0.0.1");
    assert(Number.isInteger(bridgeConfig.port) && bridgeConfig.port > 0);
    assert.deepEqual(Object.keys(bridgeConfig).sort(), ["enabled", "host", "port"]);
    assert(bridgeToken.length >= 32);
    assert.equal(JSON.stringify(launchResult).includes(bridgeToken), false);

    const launchedDescriptorFile = path.join(stateRoot, `${launchRun}.json`);
    const launchedDescriptor = JSON.parse(await fs.readFile(launchedDescriptorFile, "utf8"));
    assert.equal(launchedDescriptor.token, bridgeToken);
    assert.equal(launchedDescriptor.process_id, launched.process_id);
    assert.equal(Object.keys(launchedDescriptor).length, 7);

    const launchedStatusResult = await client.callTool("minecraft_client_status", { run_id: launchRun });
    allToolResponses.push(launchedStatusResult);
    assert.equal(JSON.stringify(launchedStatusResult).includes(bridgeToken), false);

    const launchedFrameResult = await client.callTool("minecraft_client_frame", { run_id: launchRun });
    allToolResponses.push(launchedFrameResult);
    assert.deepEqual(
      Buffer.from(launchedFrameResult.content.find((block) => block.type === "image").data, "base64"),
      PNG
    );

    const queryExpectations = [
      ["capabilities", undefined, "/control/capabilities"],
      ["state", 32, "/control/state?radius=32"],
      ["screen", undefined, "/control/screen"],
      ["keymaps", undefined, "/control/keymaps"]
    ];
    for (const [kind, radius, expectedPath] of queryExpectations) {
      const args = { run_id: launchRun, kind };
      if (radius !== undefined) {
        args.radius = radius;
      }
      const result = await client.callTool("minecraft_client_query", args);
      allToolResponses.push(result);
      const payload = textPayload(result);
      assert.equal(payload.kind, kind);
      assert.equal(payload.result.path, expectedPath);
      assert.equal(JSON.stringify(result).includes(bridgeToken), false);
    }
    await expectToolError(client, "minecraft_client_query", {
      run_id: launchRun,
      kind: "state",
      radius: 33
    }, /radius/);
    // The schemas are flat objects, so runtime must still refuse fields from another kind.
    await expectToolError(client, "minecraft_client_query", {
      run_id: launchRun,
      kind: "screen",
      radius: 4
    }, /exactly/);
    await expectToolError(client, "minecraft_client_input", {
      run_id: launchRun,
      kind: "release_all",
      mapping: "key.jump"
    }, /exactly/);
    await expectToolError(client, "minecraft_client_input", {
      run_id: launchRun,
      kind: "key",
      mapping: "key.jump",
      action: "scroll"
    }, /action/);

    for (const action of ["press", "release", "tap"]) {
      allToolResponses.push(await client.callTool("minecraft_client_input", {
        run_id: launchRun,
        kind: "key",
        mapping: "key.jump",
        action
      }));
    }
    for (const [key, action] of [
      ["enter", "tap"],
      ["key.keyboard.escape", "press"],
      ["f1", "release"]
    ]) {
      allToolResponses.push(await client.callTool("minecraft_client_input", {
        run_id: launchRun,
        kind: "raw_key",
        key,
        action
      }));
    }
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "look",
      yaw: 12.5,
      pitch: -4,
      relative: true
    }));
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "mouse",
      button: 0,
      action: "press"
    }));
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "mouse",
      x: 120,
      y: 80,
      button: 1,
      action: "click"
    }));
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "mouse",
      scrollY: -1,
      action: "scroll"
    }));
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "text",
      text: "MineClient Bridge",
      submit: true
    }));
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "text",
      text: "/say submitted through chat",
      submit: true
    }));
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "command",
      command: "/say submitted directly"
    }));
    const maximumCommand = "x".repeat(256);
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "command",
      command: maximumCommand
    }));
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "command",
      command: `/${maximumCommand}`
    }));
    await expectToolError(client, "minecraft_client_input", {
      run_id: launchRun,
      kind: "command",
      command: ""
    }, /command/);
    await expectToolError(client, "minecraft_client_input", {
      run_id: launchRun,
      kind: "command",
      command: `/${"x".repeat(257)}`
    }, /1 to 256/);
    allToolResponses.push(await client.callTool("minecraft_client_input", {
      run_id: launchRun,
      kind: "release_all"
    }));

    const fixtureLog = path.join(launchRoot, "game", "captures", "fixture-requests.jsonl");
    await waitFor(() => existsWithContent(fixtureLog), 3000, "fixture request log");
    const fixtureRequests = (await fs.readFile(fixtureLog, "utf8"))
      .trim()
      .split(/\r?\n/)
      .filter(Boolean)
      .map((line) => JSON.parse(line));
    const keyBodies = fixtureRequests
      .filter((entry) => entry.path === "/control/key")
      .map((entry) => entry.body);
    assert.deepEqual(keyBodies, [
      { mapping: "key.jump", action: "down" },
      { mapping: "key.jump", action: "up" },
      { mapping: "key.jump", action: "click" }
    ]);
    assert.deepEqual(
      fixtureRequests
        .filter((entry) => entry.path === "/control/raw-key")
        .map((entry) => entry.body),
      [
        { key: "enter", action: "click" },
        { key: "key.keyboard.escape", action: "down" },
        { key: "f1", action: "up" }
      ]
    );
    assert.deepEqual(
      fixtureRequests.find((entry) => entry.path === "/control/look").body,
      { yaw: 12.5, pitch: -4, relative: true }
    );
    assert.deepEqual(
      fixtureRequests.find(
        (entry) => entry.path === "/control/mouse" && entry.body?.action === "down"
      ).body,
      { action: "down", button: 0 }
    );
    assert.deepEqual(
      fixtureRequests.find(
        (entry) => entry.path === "/control/mouse" && entry.body?.action === "click"
      ).body,
      { x: 120, y: 80, action: "click", button: 1 }
    );
    assert.deepEqual(
      fixtureRequests.find(
        (entry) => entry.path === "/control/mouse" && entry.body?.action === "scroll"
      ).body,
      { action: "scroll", scrollY: -1 }
    );
    assert.deepEqual(
      fixtureRequests
        .filter((entry) => entry.path === "/control/text")
        .map((entry) => entry.body),
      [
        { text: "MineClient Bridge", submit: true },
        { text: "/say submitted through chat", submit: true }
      ]
    );
    assert.deepEqual(
      fixtureRequests
        .filter((entry) => entry.path === "/control/command")
        .map((entry) => entry.body),
      [
        { command: "/say submitted directly" },
        { command: maximumCommand },
        { command: `/${maximumCommand}` }
      ]
    );
    const heldRawKeyIndex = fixtureRequests.findIndex(
      (entry) => entry.path === "/control/raw-key" && entry.body?.action === "down"
    );
    const releaseAllIndex = fixtureRequests.findIndex(
      (entry) => entry.path === "/control/release-all"
    );
    assert(heldRawKeyIndex >= 0);
    assert(releaseAllIndex > heldRawKeyIndex);

    await fs.writeFile(path.join(launchRoot, "fixture-input-isolation.json"), "null");
    const launchedClose = await client.callTool(
      "minecraft_client_close",
      { run_id: launchRun },
      20_000
    );
    allToolResponses.push(launchedClose);
    assert.equal(textPayload(launchedClose).closed, true);
    await waitFor(
      () => exists(path.join(launchRoot, "game", "captures", "fixture-closed.marker")),
      3000,
      "fixture close marker"
    );
    assert.equal(await exists(launchedDescriptorFile), false);

    const fixturePidPath = path.join(launchRoot, "fixture-child-pid.txt");
    if (await exists(fixturePidPath)) {
      fixturePid = Number.parseInt((await fs.readFile(fixturePidPath, "utf8")).trim(), 10);
    }

    manualIdentity.run_id = exitRun;
    manualIdentity.desktop_name = `ExitDesktop-${exitRun}`;
    manualIdentity.runtime_root = exitRuntime;
    manualIdentity.evidence_root = exitEvidence;
    manualBridge.requests.length = 0;
    const exitDescriptor = {
      ...manualIdentity,
      base_url: manualBridge.baseUrl,
      token: manualToken
    };
    const exitRegistered = await client.callTool("minecraft_client_register", exitDescriptor);
    allToolResponses.push(exitRegistered);
    assert.equal(textPayload(exitRegistered).registered, true);

    await client.end();
    client = null;
    assert(
      manualBridge.requests.some((entry) => entry.path === "/control/release-all"),
      "server exit must best-effort release registered reachable sessions"
    );

    for (const response of allToolResponses) {
      const serialized = JSON.stringify(response);
      assert.equal(serialized.includes(manualToken), false);
      assert.equal(serialized.includes(bridgeToken), false);
    }

    assert.equal(await processIsAlive(fixturePid), false);
    assert.equal(clientStderrWasEmpty(), true);
    process.stdout.write(
      `SELF_TEST_OK tools=7 launch=ready register=exact frame=png query=4 input=15 isolation=capability-gated native-callbacks=integer-min-2 jar=4-rejections unsafe-launch=6-closed close=pid-exit exit=release secrets=redacted\n`
    );

    function clientStderrWasEmpty() {
      return client === null ? lastClientStderr.length === 0 : client.stderrText.length === 0;
    }
  } finally {
    if (client) {
      lastClientStderr = client.stderrText;
      await client.end().catch(() => client.kill());
    }
    if (manualBridge) {
      await manualBridge.close();
    }
    if (await processIsAlive(fixturePid)) {
      try {
        process.kill(fixturePid, "SIGTERM");
      } catch {
        // The test-created fixture may have exited between the liveness check and termination.
      }
    }
    for (const root of createdRoots) {
      await safeRemoveTree(root, PREPARED_PARENT, "mineclient-");
    }
    await safeRemoveTree(stateRoot, HERE, "self-test-");
  }
}

async function runZip64DirectoryTests() {
  const sourceBytes = await fs.readFile(SERVER_FILE);
  const source = sourceBytes.toString("utf8");
  const sourceSha256 = createHash("sha256").update(sourceBytes).digest("hex");
  const helperNames = [
    "assertPreparedInputIsolationJar", "readJarDirectory", "readJarEntry", "readJarBytes",
    "assertNormalDirectory", "assertNormalFile", "sameWindowsPath", "stripExtendedPrefix", "isPlainObject"
  ];
  const helperText = helperNames.map((name) => {
    const definitions = [...source.matchAll(new RegExp(`^(?:async )?function ${name}\\(`, "gm"))];
    assert.equal(definitions.length, 1, `Expected exactly one server helper ${name}`);
    const rest = source.slice(definitions[0].index);
    const next = rest.slice(1).search(/\n(?:async )?function [A-Za-z0-9_]+\(/);
    assert(next >= 0, `Missing boundary after server helper ${name}`);
    return rest.slice(0, next + 1);
  }).join("\n");
  const constantText = ["BRIDGE_CLASS_ROOT", "ISOLATION_MIXINS", "ISOLATION_MIXIN_CONFIG"].map((name) => {
    const definitions = [...source.matchAll(new RegExp(`^const ${name} = .*;\\r?$`, "gm"))];
    assert.equal(definitions.length, 1, `Expected exactly one server constant ${name}`);
    return definitions[0][0];
  }).join("\n");

  // Compile trusted source helpers only; do not import the server or touch a live mod directory.
  const script = new vm.Script(`${constantText}\n${helperText}\n` +
    "const entryRead = readJarEntry;\n" +
    "readJarEntry = async (...args) => { observeEntry(args[2]); return entryRead(...args); };\n" +
    "({ readJarDirectory, assertPreparedInputIsolationJar })", { filename: SERVER_FILE });
  function load(filesystem = {}, observeEntry = () => {}) {
    return script.runInNewContext({ fs: filesystem, path, Buffer, inflateRawSync, observeEntry }, { timeout: 1000 });
  }
  const current = load();
  const cases = [];
  async function check(name, body) {
    try {
      await body();
    } catch (error) {
      throw new Error(`ZIP64 case failed: ${name}`, { cause: error });
    }
    cases.push(name);
  }
  function memoryJar(bytes) {
    return {
      async stat() { return { size: bytes.length }; },
      async read(target, targetOffset, length, position) {
        const count = Math.max(0, Math.min(length, bytes.length - position));
        bytes.copy(target, targetOffset, position, position + count);
        return { bytesRead: count };
      },
      async close() {}
    };
  }
  function metadataZip(count, { zip64 = false, countOnly = false, comment = 0, extended = 0 } = {}) {
    const prefix = Buffer.alloc(8);
    const directory = Buffer.concat(Array.from({ length: count }, (_, index) => {
      const name = Buffer.from(`entry-${String(index).padStart(6, "0")}`);
      const header = Buffer.alloc(46);
      header.writeUInt32LE(0x02014b50);
      header.writeUInt16LE(name.length, 28);
      return Buffer.concat([header, name]);
    }));
    const directoryOffset = prefix.length;
    const recordOffset = directoryOffset + directory.length;
    const record = Buffer.alloc(zip64 ? 56 + extended : 0);
    const locator = Buffer.alloc(zip64 ? 20 : 0);
    if (zip64) {
      record.writeUInt32LE(0x06064b50);
      record.writeBigUInt64LE(BigInt(44 + extended), 4);
      record.writeUInt16LE(45, 12);
      record.writeUInt16LE(45, 14);
      record.writeBigUInt64LE(BigInt(count), 24);
      record.writeBigUInt64LE(BigInt(count), 32);
      record.writeBigUInt64LE(BigInt(directory.length), 40);
      record.writeBigUInt64LE(BigInt(directoryOffset), 48);
      locator.writeUInt32LE(0x07064b50);
      locator.writeBigUInt64LE(BigInt(recordOffset), 8);
      locator.writeUInt32LE(1, 16);
    }
    const end = Buffer.alloc(22 + comment);
    end.writeUInt32LE(0x06054b50);
    end.writeUInt16LE(zip64 ? 0xffff : count, 8);
    end.writeUInt16LE(zip64 ? 0xffff : count, 10);
    end.writeUInt32LE(zip64 && !countOnly ? 0xffffffff : directory.length, 12);
    end.writeUInt32LE(zip64 && !countOnly ? 0xffffffff : directoryOffset, 16);
    end.writeUInt16LE(comment, 20);
    return {
      bytes: Buffer.concat([prefix, directory, record, locator, end]),
      record: recordOffset, locator: recordOffset + record.length,
      end: recordOffset + record.length + locator.length, directory: directoryOffset
    };
  }
  function altered(fixture, edit) {
    const result = { ...fixture, bytes: Buffer.from(fixture.bytes) };
    edit(result.bytes, result);
    return result.bytes;
  }
  async function reject(name, bytes, expression) {
    await check(name, () => assert.rejects(current.readJarDirectory(memoryJar(bytes)), expression));
  }

  const ordinary = metadataZip(3);
  const legacyMaximum = metadataZip(65535);
  const zip64 = metadataZip(65536, { zip64: true });
  const small64 = metadataZip(3, { zip64: true });
  await check("ordinary legacy directory retains its Map entry contract", async () => {
    const entries = await current.readJarDirectory(memoryJar(ordinary.bytes));
    const actual = JSON.parse(JSON.stringify([...entries]));
    assert.deepEqual(actual, Array.from({ length: 3 }, (_, index) => [
      `entry-${String(index).padStart(6, "0")}`,
      { flags: 0, method: 0, compressedSize: 0, size: 0, offset: 0, directoryOffset: 8 }
    ]));
  });
  await check("empty legacy directory", async () => {
    assert.equal((await current.readJarDirectory(memoryJar(metadataZip(0).bytes))).size, 0);
  });
  await check("legacy exactly 65535 without locator", async () => {
    assert.equal((await current.readJarDirectory(memoryJar(legacyMaximum.bytes))).size, 65535);
  });
  await check("ZIP64 count 65536", async () => {
    assert.equal((await current.readJarDirectory(memoryJar(zip64.bytes))).size, 65536);
  });
  for (const [name, fixture, count] of [
    ["ZIP64 finite classic directory fields", metadataZip(65536, { zip64: true, countOnly: true }), 65536],
    ["ZIP64 exact 65535", metadataZip(65535, { zip64: true }), 65535],
    ["ZIP64 below count threshold", small64, 3],
    ["ZIP64 locator outside maximum-comment tail", metadataZip(3, { zip64: true, comment: 65535 }), 3],
    ["ZIP64 extended metadata record", metadataZip(3, { zip64: true, extended: 8 }), 3]
  ]) {
    await check(name, async () => assert.equal((await current.readJarDirectory(memoryJar(fixture.bytes))).size, count));
  }
  await reject("missing ZIP64 locator", altered(small64, (b, p) => b.writeUInt32LE(0, p.locator)), /locator is missing/);
  await reject("multidisk locator disk", altered(small64, (b, p) => b.writeUInt32LE(1, p.locator + 4)), /multi-disk ZIP64 locator/);
  await reject("multidisk locator total", altered(small64, (b, p) => b.writeUInt32LE(2, p.locator + 16)), /multi-disk ZIP64 locator/);
  await reject("unsafe locator offset", altered(small64, (b, p) => b.writeBigUInt64LE(9007199254740992n, p.locator + 8)), /Unsafe ZIP64/);
  await reject("out-of-bounds locator offset", altered(small64, (b, p) => b.writeBigUInt64LE(BigInt(p.locator), p.locator + 8)), /record offset/);
  await reject("missing ZIP64 record signature", altered(small64, (b, p) => b.writeUInt32LE(0, p.record)), /record is missing/);
  await reject("short ZIP64 record declaration", altered(small64, (b, p) => b.writeBigUInt64LE(43n, p.record + 4)), /record size/);
  await reject("oversized ZIP64 record declaration", altered(small64, (b, p) => b.writeBigUInt64LE(1000n, p.record + 4)), /record size/);
  await reject("unsafe ZIP64 record size", altered(small64, (b, p) => b.writeBigUInt64LE(9007199254740992n, p.record + 4)), /Unsafe ZIP64/);
  await reject("multidisk ZIP64 record", altered(small64, (b, p) => b.writeUInt32LE(1, p.record + 16)), /multi-disk ZIP64 directory/);
  await reject("ZIP64 per-disk count mismatch", altered(small64, (b, p) => b.writeBigUInt64LE(2n, p.record + 24)), /metadata mismatch/);
  await reject("unsafe ZIP64 count", altered(small64, (b, p) => b.writeBigUInt64LE(9007199254740992n, p.record + 32)), /Unsafe ZIP64/);
  await reject("unsafe ZIP64 directory size", altered(small64, (b, p) => b.writeBigUInt64LE(9007199254740992n, p.record + 40)), /Unsafe ZIP64/);
  await reject("unsafe ZIP64 directory offset", altered(small64, (b, p) => b.writeBigUInt64LE(9007199254740992n, p.record + 48)), /Unsafe ZIP64/);
  await reject("directory exceeds retained 16MiB bound", altered(small64, (b, p) => b.writeBigUInt64LE(16777217n, p.record + 40)), /invalid ZIP directory/);
  await reject("directory overlaps ZIP64 record", altered(small64, (b, p) => b.writeBigUInt64LE(BigInt(p.record), p.record + 48)), /invalid ZIP directory/);
  await reject("impossible ZIP64 count vs directory", altered(small64, (b, p) => {
    b.writeBigUInt64LE(10000n, p.record + 24);
    b.writeBigUInt64LE(10000n, p.record + 32);
  }), /invalid ZIP directory/);
  await reject("finite classic metadata mismatch", altered(small64, (b, p) => b.writeUInt16LE(2, p.end + 10)), /metadata mismatch/);
  await reject("legacy multidisk", altered(ordinary, (b, p) => b.writeUInt16LE(1, p.end + 4)), /multi-disk ZIP directory/);
  await reject("legacy disk-count mismatch", altered(ordinary, (b, p) => b.writeUInt16LE(2, p.end + 8)), /counts mismatch/);
  await reject("multidisk directory entry", altered(ordinary, (b, p) => b.writeUInt16LE(1, p.directory + 34)), /multi-disk ZIP entry/);
  await reject("malformed directory signature", altered(ordinary, (b, p) => b.writeUInt32LE(0, p.directory)), /Malformed ZIP directory/);
  await reject("truncated directory entry", altered(ordinary, (b, p) => b.writeUInt16LE(65535, p.directory + 28)), /Truncated ZIP directory/);
  await reject("duplicate directory name", altered(ordinary, (b, p) => b.copy(b, p.directory + 58 + 46, p.directory + 46, p.directory + 58)), /Duplicate ZIP entry/);
  await reject("directory length/count mismatch", altered(ordinary, (b, p) => {
    b.writeUInt16LE(2, p.end + 8);
    b.writeUInt16LE(2, p.end + 10);
  }), /length mismatch/);
  await reject("truncated archive end", ordinary.bytes.subarray(0, ordinary.bytes.length - 1), /end directory is missing/);

  function capabilityZip({ magic = true, required = true, mixins = true, method = 0, large = false } = {}) {
    const names = ["ClientInputIsolation", "mixin/InputConstantsMixin", "mixin/MouseHandlerMixin", "mixin/KeyboardHandlerMixin"];
    const files = names.map((name) => ({
      name: `io/github/campione01/mineclientbridge/${name}.class`,
      data: large ? Buffer.alloc(262145) : Buffer.from(magic ? "cafebabe00000041" : "0000000000000041", "hex")
    }));
    files.push({ name: "mineclient_bridge.mixins.json", data: Buffer.from(JSON.stringify({
      required, package: "io.github.campione01.mineclientbridge.mixin",
      client: mixins ? names.slice(1).map((name) => name.slice(6)) : [], injectors: { defaultRequire: 1 }
    })) });
    let position = 0;
    const locals = [], directoryEntries = [];
    for (const file of files) {
      const name = Buffer.from(file.name);
      const data = method === 8 ? deflateRawSync(file.data) : file.data;
      const crc = zipCrc32(file.data);
      const local = Buffer.alloc(30);
      local.writeUInt32LE(0x04034b50);
      local.writeUInt16LE(method, 8);
      local.writeUInt32LE(crc, 14);
      local.writeUInt32LE(data.length, 18);
      local.writeUInt32LE(file.data.length, 22);
      local.writeUInt16LE(name.length, 26);
      locals.push(local, name, data);
      const header = Buffer.alloc(46);
      header.writeUInt32LE(0x02014b50);
      header.writeUInt16LE(method, 10);
      header.writeUInt32LE(crc, 16);
      header.writeUInt32LE(data.length, 20);
      header.writeUInt32LE(file.data.length, 24);
      header.writeUInt16LE(name.length, 28);
      header.writeUInt32LE(position, 42);
      directoryEntries.push(header, name);
      position += local.length + name.length + data.length;
    }
    const directory = Buffer.concat(directoryEntries);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50);
    end.writeUInt16LE(files.length, 8);
    end.writeUInt16LE(files.length, 10);
    end.writeUInt32LE(directory.length, 12);
    end.writeUInt32LE(position, 16);
    return Buffer.concat([...locals, directory, end]);
  }
  function mockDirectory(files) {
    const mods = "C:\\zip64-directory-tests\\mods";
    const entries = new Map(Object.entries(files).map(([name, bytes]) => [path.join(mods, name), bytes]));
    return { mods, fs: {
      async lstat(value) {
        const directory = value === mods;
        const bytes = entries.get(value);
        assert(directory || bytes, "Unexpected synthetic filesystem path");
        return { size: bytes?.length ?? 0, isDirectory: () => directory, isFile: () => !directory, isSymbolicLink: () => false };
      },
      async realpath(value) { return value; },
      async readdir() { return Object.keys(files).map((name) => ({ name })); },
      async open(value) { return memoryJar(entries.get(value)); }
    } };
  }
  for (const method of [0, 8]) {
    await check(`Bridge stored/deflate capability pass ${method}`, async () => {
      const data = mockDirectory({ "bridge.jar": capabilityZip({ method }) });
      await load(data.fs).assertPreparedInputIsolationJar(data.mods);
    });
  }
  for (const [name, bytes, expected] of [
    ["Bridge invalid class magic refusal", capabilityZip({ magic: false }), /invalid class/],
    ["Bridge required=false refusal", capabilityZip({ required: false }), /fail-closed client mixin/],
    ["Bridge missing mixin refusal", capabilityZip({ mixins: false }), /fail-closed client mixin/],
    ["Bridge size cap unchanged", capabilityZip({ large: true }), /Unsupported input isolation/]
  ]) {
    await check(name, async () => {
      const data = mockDirectory({ "bridge.jar": bytes });
      await assert.rejects(load(data.fs).assertPreparedInputIsolationJar(data.mods), expected);
    });
  }
  await check("Bridge exactly-one guard unchanged", async () => {
    const data = mockDirectory({ "one.jar": capabilityZip(), "two.jar": capabilityZip() });
    await assert.rejects(load(data.fs).assertPreparedInputIsolationJar(data.mods), /found 2/);
  });
  await check("ZIP64 non-Bridge plus valid Bridge verifies", async () => {
    const data = mockDirectory({ "many.jar": zip64.bytes, "bridge.jar": capabilityZip() });
    await load(data.fs).assertPreparedInputIsolationJar(data.mods);
  });
  await check("ZIP64 directory well above the legacy entry limit", async () => {
    const fixture = metadataZip(70000, { zip64: true });
    assert.equal((await current.readJarDirectory(memoryJar(fixture.bytes))).size, 70000);
  });
  await check("mods directory with ordinary JARs reads only the five public Bridge entries", async () => {
    const files = Object.fromEntries(Array.from({ length: 9 }, (_, index) => [`ordinary-${index}.jar`, metadataZip(0).bytes]));
    files["bridge.jar"] = capabilityZip();
    const data = mockDirectory(files);
    const reads = [];
    await load(data.fs, (name) => reads.push(name)).assertPreparedInputIsolationJar(data.mods);
    assert.equal(Object.keys(files).length, 10);
    assert.deepEqual(reads, [
      "io/github/campione01/mineclientbridge/ClientInputIsolation.class",
      "io/github/campione01/mineclientbridge/mixin/InputConstantsMixin.class",
      "io/github/campione01/mineclientbridge/mixin/MouseHandlerMixin.class",
      "io/github/campione01/mineclientbridge/mixin/KeyboardHandlerMixin.class",
      "mineclient_bridge.mixins.json"
    ]);
  });
  assert.equal(cases.length, 45);
  process.stdout.write(`ZIP64_TEST_OK cases=${cases.length} source_sha256=${sourceSha256}\n`);
}

function fixtureJar(variant = "current") {
  const prefix = "io/github/campione01/mineclientbridge/";
  const mixins = ["InputConstantsMixin", "MouseHandlerMixin", "KeyboardHandlerMixin"];
  const classNames = variant === "old" ? ["BridgeServer"] :
    ["ClientInputIsolation", ...mixins.filter((name) => variant !== "missing-mixin" || name !== "KeyboardHandlerMixin")
      .map((name) => `mixin/${name}`)];
  const files = classNames.map((name) => [
    `${prefix}${name}.class`, Buffer.from([0xca, 0xfe, 0xba, 0xbe, 0, 0, 0, 65])
  ]);
  if (variant !== "old") {
    files.push(["mineclient_bridge.mixins.json", Buffer.from(JSON.stringify({
      required: true,
      package: "io.github.campione01.mineclientbridge.mixin",
      client: variant === "bad-config" ? ["InputConstantsMixin"] : mixins,
      injectors: { defaultRequire: 1 }
    }))]);
  }
  const locals = [];
  const directory = [];
  let offset = 0;
  for (const [name, bytes] of files) {
    const filename = Buffer.from(name);
    const compressed = deflateRawSync(bytes);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(8, 8);
    local.writeUInt32LE(zipCrc32(bytes), 14);
    local.writeUInt32LE(compressed.length, 18);
    local.writeUInt32LE(bytes.length, 22);
    local.writeUInt16LE(filename.length, 26);
    locals.push(local, filename, compressed);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(8, 10);
    central.writeUInt32LE(zipCrc32(bytes), 16);
    central.writeUInt32LE(compressed.length, 20);
    central.writeUInt32LE(bytes.length, 24);
    central.writeUInt16LE(filename.length, 28);
    central.writeUInt32LE(offset, 42);
    directory.push(central, filename);
    offset += local.length + filename.length + compressed.length;
  }
  const central = Buffer.concat(directory);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(central.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, central, end]);
}

function zipCrc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

async function createPreparedFixture(root, runId, desktopName, mismatchRoot) {
  const game = path.join(root, "game");
  const launchDirectory = path.join(root, "launch");
  await fs.mkdir(game, { recursive: true });
  await fs.mkdir(path.join(game, "mods"), { recursive: true });
  await fs.writeFile(path.join(game, "mods", "renamed-client.jar"), fixtureJar());
  await fs.mkdir(launchDirectory, { recursive: true });
  await fs.copyFile(fileURLToPath(import.meta.url), path.join(root, "fixture-bridge.mjs"));
  await fs.writeFile(path.join(launchDirectory, "java-arguments.txt"), "# self-test fixture\n", "utf8");
  await fs.writeFile(
    path.join(root, "final-preflight.json"),
    `${JSON.stringify({
      schema_version: 1,
      run_id: runId,
      root: mismatchRoot ? `${root}-mismatch` : root,
      desktop: desktopName
    }, null, 2)}\n`,
    "utf8"
  );

  const launchScript = [
    "$ErrorActionPreference='Stop'",
    `$node='${powerShellQuote(process.execPath)}'`,
    "$root=$PSScriptRoot",
    "$test=Join-Path $root 'fixture-bridge.mjs'",
    "try {",
    "  Set-Content -LiteralPath (Join-Path $root 'fixture-launch-started.txt') -Value 'started' -Encoding ascii",
    "  $child=Start-Process -FilePath $node -ArgumentList @($test,'--fixture-bridge',$root) -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $root 'fixture-stdout.log') -RedirectStandardError (Join-Path $root 'fixture-stderr.log') -PassThru",
    "  Set-Content -LiteralPath (Join-Path $root 'fixture-child-pid.txt') -Value $child.Id -Encoding ascii",
    "} catch {",
    "  ($_ | Out-String) | Set-Content -LiteralPath (Join-Path $root 'fixture-launch-error.txt') -Encoding utf8",
    "  throw",
    "}"
  ].join("\r\n");
  await fs.writeFile(path.join(root, "launch.ps1"), `${launchScript}\r\n`, "utf8");
}

function powerShellQuote(value) {
  return String(value).replaceAll("'", "''");
}

async function runFixtureBridge(root) {
  if (typeof root !== "string" || path.win32.basename(root).length === 0) {
    throw new Error("Fixture root is required");
  }
  assert.equal(process.env.MINECLIENT_BRIDGE_ISOLATED_INPUT, "true");
  const config = JSON.parse(
    await fs.readFile(path.join(root, "game", "config", "mineclient-bridge.json"), "utf8")
  );
  const token = await fs.readFile(
    path.join(root, "game", "config", "mineclient-bridge.token"),
    "utf8"
  );
  const identity = {
    run_id: process.env.MINECLIENT_BRIDGE_RUN_ID,
    process_id: process.pid,
    desktop_name: process.env.MINECLIENT_BRIDGE_DESKTOP_NAME,
    runtime_root: process.env.MINECLIENT_BRIDGE_RUNTIME_ROOT,
    evidence_root: process.env.MINECLIENT_BRIDGE_EVIDENCE_ROOT
  };
  await fs.mkdir(identity.evidence_root, { recursive: true });
  const requestLog = path.join(identity.evidence_root, "fixture-requests.jsonl");
  const closeMarker = path.join(identity.evidence_root, "fixture-closed.marker");
  const bridge = await createBridgeServer({
    identity,
    token,
    host: config.host,
    port: config.port,
    closeOnRequest: true,
    async getInputIsolation() {
      const override = path.join(root, "fixture-input-isolation.json");
      return await exists(override) ? JSON.parse(await fs.readFile(override, "utf8")) : INPUT_ISOLATION;
    },
    record(entry) {
      fsSync.appendFileSync(requestLog, `${JSON.stringify(entry)}\n`, "utf8");
    },
    onClose() {
      fsSync.writeFileSync(closeMarker, "closed\n", "utf8");
    }
  });
  await bridge.closed;
}

async function createBridgeServer(options) {
  const requests = [];
  let resolveClosed;
  const closed = new Promise((resolve) => {
    resolveClosed = resolve;
  });
  const server = http.createServer(async (request, response) => {
    try {
      if (request.headers.authorization !== `Bearer ${options.token}`) {
        writeJson(response, 401, { error: "unauthorized" });
        return;
      }
      const url = new URL(request.url, "http://127.0.0.1");
      const body = await readRequestBody(request);
      const record = {
        method: request.method,
        path: `${url.pathname}${url.search}`,
        body: body ?? null
      };
      requests.push(record);
      options.record?.(record);

      if (request.method === "GET" && url.pathname === "/control/status") {
        writeJson(response, 200, {
          ...options.identity,
          input_isolation: options.getInputIsolation ? await options.getInputIsolation() :
            Object.hasOwn(options, "inputIsolation") ? options.inputIsolation : INPUT_ISOLATION,
          ready: true,
          token_echo: options.token,
          diagnostic: `authenticated:${options.token}`
        });
        return;
      }
      if (request.method === "GET" && url.pathname === "/control/frame") {
        response.writeHead(200, {
          "content-type": "image/png",
          "content-length": PNG.length
        });
        response.end(PNG);
        return;
      }
      if (
        request.method === "GET" &&
        ["/control/capabilities", "/control/state", "/control/screen", "/control/keymaps"].includes(url.pathname)
      ) {
        writeJson(response, 200, {
          path: `${url.pathname}${url.search}`,
          ok: true,
          secret_token: options.token
        });
        return;
      }
      if (
        request.method === "POST" &&
        [
          "/control/key",
          "/control/raw-key",
          "/control/look",
          "/control/mouse",
          "/control/text",
          "/control/command",
          "/control/release-all"
        ].includes(url.pathname)
      ) {
        writeJson(response, 200, { accepted: true, token_echo: options.token });
        return;
      }
      if (request.method === "POST" && url.pathname === "/control/close") {
        writeJson(response, 200, { closed: true, token_echo: options.token });
        if (options.closeOnRequest) {
          options.onClose?.();
          setImmediate(() => server.close());
        }
        return;
      }
      writeJson(response, 404, { error: "not_found" });
    } catch {
      if (!response.headersSent) {
        writeJson(response, 500, { error: "fixture_failure" });
      } else {
        response.destroy();
      }
    }
  });
  server.once("close", () => resolveClosed());
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen({
      host: options.host ?? "127.0.0.1",
      port: options.port ?? 0,
      exclusive: true
    }, resolve);
  });
  const address = server.address();
  assert(address && typeof address !== "string");
  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    requests,
    setInputIsolation(value) { options.inputIsolation = value; },
    closed,
    async close() {
      if (!server.listening) {
        return;
      }
      await new Promise((resolve) => server.close(resolve));
    }
  };
}

async function readRequestBody(request) {
  const chunks = [];
  let total = 0;
  for await (const chunk of request) {
    total += chunk.length;
    if (total > 1024 * 1024) {
      throw new Error("request too large");
    }
    chunks.push(chunk);
  }
  if (total === 0) {
    return undefined;
  }
  return JSON.parse(Buffer.concat(chunks, total).toString("utf8"));
}

function writeJson(response, status, value) {
  const body = Buffer.from(JSON.stringify(value), "utf8");
  response.writeHead(status, {
    "content-type": "application/json",
    "content-length": body.length
  });
  response.end(body);
}

function createMcpClient(extraEnvironment) {
  const child = spawn(process.execPath, [SERVER_FILE], {
    env: { ...process.env, ...extraEnvironment },
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true
  });
  let buffer = Buffer.alloc(0);
  let nextId = 1;
  let stderrText = "";
  let ended = false;
  const pending = new Map();

  child.stdout.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (true) {
      const newlineIndex = buffer.indexOf(0x0a);
      if (newlineIndex < 0) {
        break;
      }
      const line = buffer.subarray(0, newlineIndex).toString("utf8");
      assert.notEqual(line.trim(), "", "MCP stdout must contain only JSON-RPC messages");
      const message = JSON.parse(line);
      assert.equal(message.jsonrpc, "2.0");
      buffer = buffer.subarray(newlineIndex + 1);
      const waiting = pending.get(message.id);
      if (waiting) {
        pending.delete(message.id);
        clearTimeout(waiting.timer);
        waiting.resolve(message);
      }
    }
  });
  child.stderr.on("data", (chunk) => {
    stderrText += chunk.toString("utf8");
    lastClientStderr = stderrText;
  });
  child.stdout.once("end", () => {
    assert.equal(buffer.length, 0, "MCP responses must end with a newline");
  });
  child.once("exit", (code) => {
    ended = true;
    for (const waiting of pending.values()) {
      clearTimeout(waiting.timer);
      waiting.reject(new Error(`MCP exited with code ${code}: ${stderrText}`));
    }
    pending.clear();
  });

  return {
    get stderrText() {
      return stderrText;
    },
    request(method, params, timeoutMs = 10_000) {
      const id = nextId++;
      const message = { jsonrpc: "2.0", id, method, params };
      const body = JSON.stringify(message);
      child.stdin.write(`${body}\n`);
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          pending.delete(id);
          reject(new Error(`MCP request timed out: ${method}`));
        }, timeoutMs);
        pending.set(id, { resolve, reject, timer });
      });
    },
    async callTool(name, args, timeoutMs = 10_000) {
      const response = await this.request("tools/call", { name, arguments: args }, timeoutMs);
      assert.equal(response.error, undefined);
      return response.result;
    },
    async end() {
      if (ended) {
        return;
      }
      child.stdin.end();
      await Promise.race([
        new Promise((resolve) => child.once("exit", resolve)),
        new Promise((_, reject) => setTimeout(() => reject(new Error("MCP did not exit")), 5000))
      ]);
      lastClientStderr = stderrText;
    },
    kill() {
      child.kill();
    }
  };
}

async function expectToolError(client, name, args, pattern) {
  const result = await client.callTool(name, args);
  assert.equal(result.isError, true, `${name} should return a tool error`);
  const text = result.content.find((block) => block.type === "text")?.text ?? "";
  assert.match(text, pattern);
  assert.equal(text.includes(args.token ?? "__no_token__"), false);
}

function textPayload(result) {
  const text = result.content.find((block) => block.type === "text");
  assert(text, "tool result must include text content");
  if (result.isError) {
    throw new Error(`Unexpected tool error: ${text.text}`);
  }
  return JSON.parse(text.text);
}

async function exists(target) {
  try {
    await fs.lstat(target);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT") {
      return false;
    }
    throw error;
  }
}

async function existsWithContent(target) {
  try {
    return (await fs.stat(target)).size > 0;
  } catch (error) {
    if (error?.code === "ENOENT") {
      return false;
    }
    throw error;
  }
}

async function waitFor(predicate, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error(`Timed out waiting for ${label}`);
}

async function processIsAlive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) {
    return false;
  }
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function safeRemoveTree(target, expectedParent, requiredPrefix) {
  const resolvedTarget = path.win32.resolve(target);
  const resolvedParent = path.win32.resolve(expectedParent);
  const relative = path.win32.relative(resolvedParent, resolvedTarget);
  const name = path.win32.basename(resolvedTarget);
  if (
    relative === "" ||
    relative === ".." ||
    relative.startsWith(`..${path.win32.sep}`) ||
    path.win32.isAbsolute(relative) ||
    !name.startsWith(requiredPrefix)
  ) {
    throw new Error(`Refusing unsafe self-test cleanup target: ${resolvedTarget}`);
  }
  await fs.rm(resolvedTarget, { recursive: true, force: true });
}
