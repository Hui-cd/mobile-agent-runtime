import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { AndroidAdapter } from "../src/android/android-adapter.js";
import type { AdbExecutor, CommandResult } from "../src/android/adb.js";

function output(value: string | Buffer = ""): CommandResult {
  return { stdout: Buffer.isBuffer(value) ? value : Buffer.from(value), stderr: "", exitCode: 0 };
}

class MockAdb implements AdbExecutor {
  readonly calls: string[][] = [];
  constructor(private readonly xml: string) {}

  async run(args: string[]): Promise<CommandResult> {
    this.calls.push(args);
    const command = args.join(" ");
    if (command === "get-state") return output("device\n");
    if (command.includes("uiautomator dump")) return output("UI hierarchy dumped");
    if (command === "exec-out cat /sdcard/mobile_agent_window.xml") return output(this.xml);
    if (command === "exec-out screencap -p") {
      const png = Buffer.alloc(24);
      Buffer.from([0x89, 0x50, 0x4e, 0x47]).copy(png);
      png.writeUInt32BE(1080, 16);
      png.writeUInt32BE(2400, 20);
      return output(png);
    }
    if (command === "shell dumpsys activity activities") return output("topResumedActivity=ActivityRecord{abc u0 com.example/.MainActivity t3}");
    if (command === "shell dumpsys input_method") return output("mInputShown=true");
    if (command === "shell wm size") return output("Physical size: 1080x2400");
    return output();
  }
}

const fixture = new URL("./fixtures/window.xml", import.meta.url);

test("observe returns the unified device state", async () => {
  const mock = new MockAdb(await readFile(fixture, "utf8"));
  const observation = await new AndroidAdapter({ adb: mock }).observe();
  assert.equal(observation.current_app, "com.example");
  assert.equal(observation.current_activity, ".MainActivity");
  assert.equal(observation.keyboard_visible, true);
  assert.equal(observation.ui_tree?.length, 3);
  assert.deepEqual(
    { width: observation.screen?.width, height: observation.screen?.height },
    { width: 1080, height: 2400 },
  );
  assert.equal(observation.capabilities.connected, true);
});

test("semantic click resolves a UI node and automatically observes", async () => {
  const mock = new MockAdb(await readFile(fixture, "utf8"));
  const adapter = new AndroidAdapter({ adb: mock, settlePollMs: 1, settleTimeoutMs: 100 });
  const result = await adapter.act({ action: "click", target: { text: "Search" } });
  assert.equal(result.ok, true);
  assert.equal(result.stability.stable, true);
  assert.ok(mock.calls.some((call) => call.join(" ") === "shell input tap 300 260"));
  assert.equal(result.observation.current_app, "com.example");
});

test("invoke uses a structured allowlisted Android intent", async () => {
  const mock = new MockAdb(await readFile(fixture, "utf8"));
  const adapter = new AndroidAdapter({ adb: mock, settlePollMs: 1, settleTimeoutMs: 100 });
  await adapter.invoke({ capability: "open_url", params: { url: "https://example.com" } });
  assert.ok(mock.calls.some((call) => call.join(" ") ===
    "shell am start -W -a android.intent.action.VIEW -d https://example.com"));
});
