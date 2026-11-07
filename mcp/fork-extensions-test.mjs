#!/usr/bin/env node

// [Arcane Academy fork] Tests for the fork's MCP additions: attach, saved frames, sequences,
// chat queries, settle_ticks, replacing exited registrations, and bridge error codes.
// Kept apart from self-test.mjs so upstream changes to that file merge cleanly.

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fsSync from "node:fs";
import fs from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER_FILE = path.join(HERE, "mineclient-bridge-mcp.mjs");
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

const suffix = `${process.pid}-${Date.now().toString(36)}`;
const namespace = `self-test-fork-${suffix}`;
const stateRoot = path.join(HERE, namespace);
const testRoot = path.join(fsSync.realpathSync.native(os.tmpdir()), `mineclient-fork-test-${suffix}`);
const runtimeRoot = path.join(testRoot, "runtime");
const evidenceRoot = path.join(runtimeRoot, "bridge-evidence");
const otherRuntime = path.join(testRoot, "other-runtime");
const token = `fork-token-${suffix}`;
const runId = `fork-client-${suffix}`;

let bridge;
let client;
try {
  await fs.mkdir(path.join(runtimeRoot, "config"), { recursive: true });
  await fs.mkdir(evidenceRoot, { recursive: true });
  await fs.mkdir(path.join(otherRuntime, "config"), { recursive: true });
  await fs.writeFile(path.join(runtimeRoot, "config", "mineclient-bridge.token"), `${token}\n`);
  await fs.writeFile(path.join(otherRuntime, "config", "mineclient-bridge.token"), `${token}\n`);

  bridge = await createFixtureBridge({
    run_id: runId,
    process_id: process.pid,
    desktop_name: `ForkDesktop-${suffix}`,
    runtime_root: runtimeRoot,
    evidence_root: evidenceRoot
  });
  client = createMcpClient({ NODE_ENV: "test", MINECLIENT_BRIDGE_TEST_NAMESPACE: namespace });
  await client.request("initialize", { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "fork-test", version: "1" } });

  // A registration left by a client that has exited is replaced, not refused.
  const deadPid = await exitedProcessId();
  await fs.mkdir(stateRoot, { recursive: true });
  await fs.writeFile(path.join(stateRoot, `${runId}.json`), JSON.stringify({
    run_id: runId,
    base_url: bridge.baseUrl,
    token,
    process_id: deadPid,
    desktop_name: `ForkDesktop-${suffix}`,
    runtime_root: runtimeRoot,
    evidence_root: evidenceRoot
  }));

  // attach refuses a folder the bridge does not report.
  await expectError("minecraft_client_attach", { runtime_root: otherRuntime, base_url: bridge.baseUrl }, /does not belong/);

  const attached = payload(await client.callTool("minecraft_client_attach", { runtime_root: runtimeRoot, base_url: bridge.baseUrl }));
  assert.equal(attached.registered, true);
  assert.equal(attached.session.run_id, runId);
  assert.deepEqual(attached.replaced_exited_registrations, [runId]);
  assert.equal(JSON.stringify(attached).includes(token), false, "attach must not return the token");

  const again = payload(await client.callTool("minecraft_client_attach", { runtime_root: runtimeRoot, base_url: bridge.baseUrl }));
  assert.equal(again.already_registered, true);

  // Frames save into the evidence folder and refuse to overwrite by default.
  const saved = await client.callTool("minecraft_client_frame", { run_id: runId, save_as: "first-shot", include_image: false });
  assert.equal(saved.isError, false);
  assert.equal(saved.content.some((block) => block.type === "image"), false);
  assert.deepEqual(await fs.readFile(path.join(evidenceRoot, "first-shot.png")), PNG);
  await expectError("minecraft_client_frame", { run_id: runId, save_as: "first-shot" }, /already exists/);
  assert.equal((await client.callTool("minecraft_client_frame", { run_id: runId, save_as: "first-shot.png", overwrite: true })).isError, false);
  for (const bad of ["../escape", "a\\b", "", ".hidden", "x".repeat(97)]) {
    await expectError("minecraft_client_frame", { run_id: runId, save_as: bad }, /save_as/);
  }
  await expectError("minecraft_client_frame", { run_id: runId, include_image: false }, /needs save_as/);

  // Chat query.
  const chat = payload(await client.callTool("minecraft_client_query", { run_id: runId, kind: "chat", limit: 5 }));
  assert.equal(chat.result.messages[0].text, "fixture line");
  assert(bridge.requests.some((entry) => entry.path === "/control/chat?limit=5"));
  await expectError("minecraft_client_query", { run_id: runId, kind: "chat", limit: 101 }, /limit/);

  // settle_ticks passes through and is validated.
  await client.callTool("minecraft_client_input", { run_id: runId, kind: "key", mapping: "key.hotbar.3", action: "tap", settle_ticks: 1 });
  assert.equal(bridge.requests.at(-1).body.settle_ticks, 1);
  await expectError("minecraft_client_input", { run_id: runId, kind: "release_all", settle_ticks: 1 }, /release_all/);
  await expectError("minecraft_client_input", { run_id: runId, kind: "key", mapping: "key.jump", action: "tap", settle_ticks: 41 }, /settle_ticks/);

  // Sequences run in order, apply default_settle_ticks, save frames, and run queries.
  const before = bridge.requests.length;
  const sequence = await client.callTool("minecraft_client_sequence", {
    run_id: runId,
    default_settle_ticks: 2,
    steps: [
      { kind: "key", mapping: "key.hotbar.5", action: "tap" },
      { kind: "wait", ms: 10 },
      { kind: "mouse", action: "scroll", scrollY: -1, settle_ticks: 0 },
      { kind: "command", command: "arcaneacademy debug wizard_state" },
      { kind: "query", query: "chat", limit: 3 },
      { kind: "frame", save_as: "sequence-shot" },
      { kind: "release_all" }
    ]
  });
  assert.equal(sequence.isError, false);
  const summary = JSON.parse(sequence.content[0].text);
  assert.equal(sequence.content[0].text.includes(token), false, "sequence output must not include the token");
  assert.equal(summary.steps_run, 7);
  assert.equal(summary.steps_failed, 0);
  const posts = bridge.requests.slice(before).filter((entry) => entry.method === "POST");
  assert.deepEqual(posts.map((entry) => entry.path), ["/control/key", "/control/mouse", "/control/command", "/control/release-all"]);
  assert.equal(posts[0].body.settle_ticks, 2);
  assert.equal(posts[1].body.settle_ticks, 0);
  assert.equal(posts[2].body.settle_ticks, 2);
  assert.equal(posts[3].body, null);
  assert(await exists(path.join(evidenceRoot, "sequence-shot.png")));

  // Invalid steps fail before anything runs; run_id inside a step is refused.
  const beforeInvalid = bridge.requests.length;
  await expectError("minecraft_client_sequence", {
    run_id: runId,
    steps: [{ kind: "key", mapping: "key.jump", action: "tap" }, { kind: "key", mapping: "key.jump", action: "hold" }]
  }, /step 1/);
  await expectError("minecraft_client_sequence", {
    run_id: runId, steps: [{ kind: "key", run_id: "other", mapping: "key.jump", action: "tap" }]
  }, /no run_id/);
  await expectError("minecraft_client_sequence", {
    run_id: runId, steps: Array.from({ length: 13 }, () => ({ kind: "wait", ms: 10_000 }))
  }, /limit is 120000/);
  assert.equal(bridge.requests.length, beforeInvalid, "invalid sequences must not reach the bridge");

  // A failing step stops the sequence and reports the bridge's error code.
  const failing = await client.callTool("minecraft_client_sequence", {
    run_id: runId,
    steps: [
      { kind: "command", command: "fail-not-in-world" },
      { kind: "key", mapping: "key.jump", action: "tap" }
    ]
  });
  assert.equal(failing.isError, true);
  const failed = JSON.parse(failing.content[0].text);
  assert.equal(failed.steps_run, 1);
  assert.match(failed.steps[0].error, /409.*not_in_world/);

  // Without isolation, sequences refuse input steps just as minecraft_client_input does.
  bridge.isolation = { ...INPUT_ISOLATION, enabled: false };
  const blocked = await client.callTool("minecraft_client_sequence", {
    run_id: runId, steps: [{ kind: "key", mapping: "key.jump", action: "tap" }]
  });
  assert.equal(blocked.isError, true);
  assert.match(JSON.parse(blocked.content[0].text).steps[0].error, /isolation/);
  bridge.isolation = INPUT_ISOLATION;

  // [Arcane Academy fork] Live reload schema, duration, error, sequence and isolation contract.
  const reloadTool = (await client.request("tools/list", {})).result.tools.find(t => t.name === "minecraft_client_reload_resources");
  assert.deepEqual(reloadTool.inputSchema.required, ["run_id"]);
  assert.equal(reloadTool.inputSchema.additionalProperties, false);
  assert.equal(payload(await client.callTool("minecraft_client_reload_resources", { run_id: runId })).result.duration_ms, 12);
  await expectError("minecraft_client_reload_resources", { run_id: runId, path: "other" }, /exactly|Unexpected/);
  const reloadSequence = payload(await client.callTool("minecraft_client_sequence", { run_id: runId, steps: [{ kind: "reload_resources" }] }));
  assert.equal(reloadSequence.steps[0].result.duration_ms, 12);
  bridge.reloadError = true;
  await expectError("minecraft_client_reload_resources", { run_id: runId }, /resource_reload_failed/);
  const reloadFailed = await client.callTool("minecraft_client_sequence", { run_id: runId, steps: [{ kind: "reload_resources" }, { kind: "hotswap" }] });
  assert.equal(JSON.parse(reloadFailed.content[0].text).steps_run, 1);
  assert.match(reloadFailed.content[0].text, /fixture failure/);
  bridge.reloadError = false;
  bridge.isolation = { ...INPUT_ISOLATION, enabled: false };
  await expectError("minecraft_client_reload_resources", { run_id: runId }, /isolation/);
  bridge.isolation = INPUT_ISOLATION;

  const swapTool = (await client.request("tools/list", {})).result.tools.find(t => t.name === "minecraft_client_hotswap");
  assert.deepEqual(swapTool.inputSchema.required, ["run_id"]);
  assert.equal(swapTool.inputSchema.additionalProperties, false);
  const swap = payload(await client.callTool("minecraft_client_hotswap", { run_id: runId })).result;
  assert.deepEqual(swap.swapped, ["dev.example.Hud"]);
  assert.match(swap.static_fields_note, /Static fields/);
  assert.equal(swap.restart_required, false);
  await expectError("minecraft_client_hotswap", { run_id: runId, packages: "java" }, /exactly/);
  const swapSequence = payload(await client.callTool("minecraft_client_sequence", { run_id: runId, steps: [{ kind: "hotswap" }] }));
  assert.deepEqual(swapSequence.steps[0].result.swapped, swap.swapped);
  await expectError("minecraft_client_sequence", { run_id: runId, steps: [{ kind: "hotswap", roots: "other" }] }, /step 0/);
  bridge.agentMissing = true;
  await expectError("minecraft_client_hotswap", { run_id: runId }, /agent not loaded/);
  const missingSequence = await client.callTool("minecraft_client_sequence", { run_id: runId, steps: [{ kind: "hotswap" }, { kind: "reload_resources" }] });
  assert.equal(JSON.parse(missingSequence.content[0].text).steps_run, 1);
  bridge.agentMissing = false;
  bridge.isolation = { ...INPUT_ISOLATION, enabled: false };
  await expectError("minecraft_client_hotswap", { run_id: runId }, /isolation/);
  const isolatedSwap = await client.callTool("minecraft_client_sequence", { run_id: runId, steps: [{ kind: "hotswap" }] });
  assert.equal(isolatedSwap.isError, true);
  bridge.isolation = INPUT_ISOLATION;

  console.log("FORK_EXTENSIONS_TEST_OK attach=folder frame=saved chat=query settle=passthrough sequence=ordered stale=replaced errors=coded reload=duration-error hotswap=report-agent-error");
} finally {
  await client?.end().catch(() => client?.kill());
  await bridge?.close();
  await fs.rm(stateRoot, { recursive: true, force: true });
  await fs.rm(testRoot, { recursive: true, force: true });
}

async function expectError(name, args, pattern) {
  const result = await client.callTool(name, args);
  const text = result.content.find((block) => block.type === "text")?.text ?? "";
  assert.equal(result.isError, true, `${name} ${JSON.stringify(args)} should fail`);
  assert.match(text, pattern);
  assert.equal(text.includes(token), false, "errors must not include the token");
}

function payload(result) {
  const text = result.content.find((block) => block.type === "text")?.text;
  if (result.isError) {
    throw new Error(`Unexpected tool error: ${text}`);
  }
  return JSON.parse(text);
}

async function exists(target) {
  return fs.access(target).then(() => true, () => false);
}

async function exitedProcessId() {
  const child = spawn(process.execPath, ["-e", ""], { stdio: "ignore", windowsHide: true });
  await new Promise((resolve) => child.once("exit", resolve));
  return child.pid;
}

async function createFixtureBridge(identity) {
  const state = { requests: [], isolation: INPUT_ISOLATION };
  const server = http.createServer(async (request, response) => {
    if (request.headers.authorization !== `Bearer ${token}`) {
      return json(response, 401, { error: "unauthorized" });
    }
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const raw = Buffer.concat(chunks).toString("utf8");
    const body = raw ? JSON.parse(raw) : null;
    const url = new URL(request.url, "http://127.0.0.1");
    state.requests.push({ method: request.method, path: `${url.pathname}${url.search}`, body });
    if (url.pathname === "/control/status") {
      return json(response, 200, { ...identity, input_isolation: state.isolation, token_echo: token });
    }
    if (url.pathname === "/control/frame") {
      response.writeHead(200, { "content-type": "image/png", "content-length": PNG.length });
      return response.end(PNG);
    }
    if (url.pathname === "/control/chat") {
      return json(response, 200, { ok: true, action_bar: { showing: false }, messages: [{ text: "fixture line", added_tick: 1 }] });
    }
    if (url.pathname === "/control/reload-resources") {
      return state.reloadError
        ? json(response, 500, { ok: false, error: "resource_reload_failed", detail: `fixture failure ${token}` })
        : json(response, 200, { ok: true, duration_ms: 12 });
    }
    if (url.pathname === "/control/hotswap") {
      return state.agentMissing
        ? json(response, 409, { ok: false, error: "agent_not_loaded", detail: "agent not loaded" })
        : json(response, 200, { ok: true, swapped: ["dev.example.Hud"], refused: [], transformed: [], new_on_disk: [], unchanged_count: 2, restart_required: false, static_fields_note: "Static fields keep their values; static initializers do not run again." });
    }
    if (request.method === "POST" && body?.command === "fail-not-in-world") {
      return json(response, 409, { ok: false, error: "not_in_world" });
    }
    if (request.method === "POST") {
      return json(response, 200, { ok: true, token_echo: token });
    }
    return json(response, 404, { error: "unknown_endpoint" });
  });
  await new Promise((resolve) => server.listen({ host: "127.0.0.1", port: 0 }, resolve));
  return Object.assign(state, {
    baseUrl: `http://127.0.0.1:${server.address().port}`,
    close: () => new Promise((resolve) => server.close(resolve))
  });
}

function json(response, status, value) {
  const bytes = Buffer.from(JSON.stringify(value));
  response.writeHead(status, { "content-type": "application/json", "content-length": bytes.length });
  response.end(bytes);
}

function createMcpClient(extraEnvironment) {
  const child = spawn(process.execPath, [SERVER_FILE], {
    env: { ...process.env, ...extraEnvironment },
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true
  });
  let buffer = Buffer.alloc(0);
  let nextId = 1;
  let stderr = "";
  const pending = new Map();
  child.stdout.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    let newline;
    while ((newline = buffer.indexOf(0x0a)) >= 0) {
      const message = JSON.parse(buffer.subarray(0, newline).toString("utf8"));
      buffer = buffer.subarray(newline + 1);
      pending.get(message.id)?.(message);
      pending.delete(message.id);
    }
  });
  child.stderr.on("data", (chunk) => { stderr += chunk; });
  return {
    request(method, params) {
      const id = nextId++;
      child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`);
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error(`timed out: ${method} ${stderr}`)), 20_000);
        pending.set(id, (message) => { clearTimeout(timer); resolve(message); });
      });
    },
    async callTool(name, args) {
      const response = await this.request("tools/call", { name, arguments: args });
      assert.equal(response.error, undefined);
      return response.result;
    },
    async end() {
      child.stdin.end();
      await new Promise((resolve) => child.once("exit", resolve));
    },
    kill() { child.kill(); }
  };
}
