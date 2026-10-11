#!/usr/bin/env node
// Dev-only: Feishu -> host ADB -> on-device AgentSession. Not standalone phone transport.
import * as lark from "@larksuiteoapi/node-sdk";
import { execFile } from "node:child_process";
import { mkdirSync, readFileSync, appendFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { ChannelController } from "./channel-controller.mjs";

function required(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`Missing ${name}; configure locally in .env.feishu`);
  return value;
}

const config = { appId: required("FEISHU_APP_ID"), appSecret: required("FEISHU_APP_SECRET") };
const userId = required("FEISHU_ALLOWED_OPEN_ID");
const serial = process.env.MOBILE_AGENT_EMULATOR_SERIAL ?? "emulator-5554";
if (!/^emulator-\d+$/.test(serial)) throw new Error("This dev entry only accepts emulator serials");
const adb = process.env.MOBILE_AGENT_ADB ?? join(homedir(), "Library/Android/sdk/platform-tools/adb");
const stateDir = fileURLToPath(new URL("../.channel-state/", import.meta.url));
mkdirSync(stateDir, { recursive: true, mode: 0o700 });
const ledger = join(stateDir, "feishu-message-ids.jsonl");
const seen = new Set(existsSync(ledger) ? readFileSync(ledger, "utf8").split("\n").filter(Boolean).map(JSON.parse) : []);
const execute = (command, args, timeout) => new Promise((resolve, reject) => {
  execFile(command, args, { timeout, maxBuffer: 1024 * 1024 }, (error, stdout) => error ? reject(error) : resolve(stdout.trim()));
});
const stop = () => execute(adb, ["-s", serial, "shell", "am", "broadcast", "-a", "ai.mobileagent.DEBUG_STOP_AGENT_TASK", "-p", "ai.mobileagent"], 15_000);
const client = new lark.Client({ ...config, loggerLevel: lark.LoggerLevel.fatal });
const controller = new ChannelController({
  userId,
  claim(id) {
    if (seen.has(id)) return false;
    // Persist before executing. Restart never replays an accepted message.
    appendFileSync(ledger, `${JSON.stringify(id)}\n`, { mode: 0o600 });
    seen.add(id);
    return true;
  },
  stop,
  async run() {
    const output = await execute(process.execPath, [fileURLToPath(new URL("./android-device-smoke.mjs", import.meta.url)), "--emulator", "--serial", serial, "--timeout", "90"], 110_000);
    console.log(output);
    const resultLine = output.split("\n").find((line) => line.startsWith("result: "));
    if (!resultLine) throw new Error("Missing adjudicated result");
    const result = JSON.parse(resultLine.slice(8));
    return `模拟器测试通过。时钟当前页面：${result.visible_title}。完成依据：任务记录和最后设备观察均指向时钟 App。`;
  },
  async reply(messageId, text) {
    const result = await client.im.message.reply({ path: { message_id: messageId }, data: { msg_type: "text", content: JSON.stringify({ text }) } });
    if (result.code !== 0) throw new Error("Feishu reply failed");
  },
});
const ws = new lark.WSClient({ ...config, loggerLevel: lark.LoggerLevel.fatal });
await ws.start({ eventDispatcher: new lark.EventDispatcher({}).register({
  "im.message.receive_v1": async (event) => {
    try { await controller.receive(event); } catch { console.error("Channel event failed; no automatic replay"); }
  },
}) });
console.log("Feishu connection requested; verify with a private command from the configured user.");
