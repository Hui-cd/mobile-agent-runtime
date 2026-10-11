#!/usr/bin/env node
// General-tool acceptance: goals are fixtures, never per-App execution scripts.
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { homedir } from "node:os";
import { join } from "node:path";
import { writeFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { randomUUID } from "node:crypto";
import { XMLParser } from "fast-xml-parser";
import { parseAdbDevices, selectDevice, parseRunsJsonl } from "./android-device-smoke.mjs";

const tasks = {
  clock: {
    packages: ["com.google.android.deskclock"],
    prompt: '先发现手机上有哪些可启动的应用，然后打开时钟，切换到秒表页，再切换到闹钟页。不创建或修改闹钟、不启动计时。核对最后选中的页签，只返回 JSON：{"selected_tab":"实际选中的页签名称"}。',
  },
  settings: {
    packages: ["com.android.settings", "com.google.android.settings.intelligence"],
    prompt: '打开系统设置，将设置内搜索框的内容替换为 bluetooth（本次要验证输入能力，必须实际输入一次），读取当前可见的前三条搜索结果。不切换任何设置开关。只返回 JSON：{"query":"bluetooth","items":["实际看到的结果标题"]}，恰好三条；不能读取时如实说明。',
  },
};

export function evaluateToolTask(task, record, messages) {
  const reasons = [];
  const spec = tasks[task];
  let result = record?.result;
  if (typeof result?.raw_text === "string") {
    const fenced = /^\s*```(?:json)?\s*\n([\s\S]*?)\n```\s*$/.exec(result.raw_text);
    if (fenced) { try { result = JSON.parse(fenced[1]); } catch { /* keep raw output */ } }
  }
  const evidence = record?.completion_evidence;
  const results = messages.filter((m) => m.role === "toolResult");
  const successfulIds = new Set(results.filter((m) => !m.isError).map((m) => m.toolCallId));
  const calls = messages.flatMap((m) => m.role === "assistant" ? m.content.filter((c) => c.type === "toolCall") : [])
    .filter((c) => successfulIds.has(c.id));
  const observations = results.filter((m) => !m.isError).map((m) => {
    try { return JSON.parse(m.content.filter((c) => c.type === "text").map((c) => c.text).join("")); } catch { return {}; }
  });
  const last = observations.at(-1);
  if (!spec) return { ok: false, reasons: ["unknown task"] };
  if (record?.environment !== "emulator" || record?.dev_only !== true) reasons.push("requires dev-only emulator evidence");
  if (record?.status !== "completed_unverified") reasons.push(`unexpected recorder status ${record?.status}`);
  if (!evidence?.claim_follows_tool_evidence || !spec.packages.includes(evidence?.last_observation?.current_app) || !spec.packages.includes(last?.current_app)) reasons.push("missing final on-device evidence in target app");
  if (task === "clock") {
    if (!calls.some((c) => c.name === "device_observe" && c.arguments.include_apps === true)) reasons.push("application discovery not exercised");
    if (calls.filter((c) => c.name === "device_act" && c.arguments.action === "click").length < 2) reasons.push("two successful semantic clicks required");
    if (!observations.slice(0, -1).some((o) => o.ui_tree?.some((n) => n.visible && n.selected && /stopwatch|秒表/i.test(`${n.text ?? ""} ${n.content_description ?? ""}`)))) reasons.push("intermediate selected Stopwatch tab was not observed");
    const selected = last?.ui_tree?.some((n) => n.visible && n.selected && /alarm|闹钟/i.test(`${n.text ?? ""} ${n.content_description ?? ""}`));
    if (!selected) reasons.push("final observed selected tab is not Alarm");
    if (!/alarm|闹钟/i.test(result?.selected_tab ?? "")) reasons.push("final answer does not match Alarm");
  } else {
    if (!calls.some((c) => c.name === "device_act" && c.arguments.action === "input" && c.arguments.value === "bluetooth")) reasons.push("successful Unicode/text input not exercised");
    const text = (last?.ui_tree ?? []).filter((n) => n.visible).map((n) => `${n.text ?? ""} ${n.content_description ?? ""}`).join("\n").toLowerCase();
    if (!last?.ui_tree?.some((n) => n.visible && n.editable && n.text?.toLowerCase() === "bluetooth")) reasons.push("final search field does not contain bluetooth");
    const items = result?.items;
    if (result?.query !== "bluetooth" || !Array.isArray(items) || items.length !== 3 || items.some((s) => typeof s !== "string" || !s.trim() || !text.includes(s.toLowerCase()))) reasons.push("three returned results must be present in final observed page");
  }
  return { ok: reasons.length === 0, reasons };
}

export async function main(argv = process.argv.slice(2)) {
  const opts = { task: "clock", serial: "emulator-5554", out: undefined };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--help") { console.log("Usage: node scripts/android-tool-use.mjs --task clock|settings [--serial emulator-5554] [--out path.json]"); return 0; }
    const key = argv[i]?.slice(2);
    if (!["task", "serial", "out"].includes(key) || !argv[i + 1]) throw new Error("Invalid arguments; use --help");
    opts[key] = argv[++i];
  }
  if (!tasks[opts.task]) throw new Error("Unknown task");
  const binary = process.env.MOBILE_AGENT_ADB ?? join(process.env.ANDROID_HOME ?? join(homedir(), "Library/Android/sdk"), "platform-tools/adb");
  const execute = promisify(execFile);
  const adb = async (...args) => (await execute(binary, ["-s", opts.serial, ...args], { timeout: 15000, maxBuffer: 32 * 1024 * 1024 })).stdout;
  const selection = selectDevice(parseAdbDevices(await adb("devices", "-l")), { serial: opts.serial, emulator: true });
  if (!selection.ok) throw new Error(selection.message);
  const readRuns = async () => {
    try { return parseRunsJsonl(await adb("shell", "run-as", "ai.mobileagent", "cat", "files/benchmarks/runs.jsonl")); }
    catch (error) { if (/No such file/.test(String(error))) return []; throw error; }
  };
  const cohort = `generic-tools-${opts.task}-${randomUUID()}`;
  // Reset fixture foreground tasks, not application data. Prevent a previous search
  // from satisfying this run without exercising text input.
  for (const packageName of tasks[opts.task].packages) await adb("shell", "am", "force-stop", packageName);
  await adb("shell", "input", "keyevent", "KEYCODE_HOME");
  await adb("shell", "am", "broadcast", "-a", "ai.mobileagent.DEBUG_RUN_AGENT_TASK", "-p", "ai.mobileagent", "--ez", "fresh_session", "true", "--es", "prompt_base64", Buffer.from(`[COHORT:${cohort}] ${tasks[opts.task].prompt}`).toString("base64"));
  console.log(`Running ${opts.task} goal through on-device Agent; no predefined click sequence.`);
  const deadline = Date.now() + 180000;
  while (Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 2000));
    const record = (await readRuns()).find((r) => r.benchmark_cohort === cohort);
    if (!record) continue;
    const xml = await adb("shell", "run-as", "ai.mobileagent", "cat", "shared_prefs/pi-agent-session.xml");
    const map = new XMLParser({ ignoreAttributes: false, attributeNamePrefix: "", parseTagValue: false }).parse(xml).map;
    const entries = Array.isArray(map.string) ? map.string : [map.string];
    const matchedPrompt = entries.find((v) => v?.name === "debug-last-prompt")?.["#text"] === record.prompt;
    const messages = matchedPrompt ? JSON.parse(entries.find((v) => v?.name === "debug-last-messages")?.["#text"] ?? "[]") : [];
    const verdict = evaluateToolTask(opts.task, record, messages);
    const trace = messages.map(({ role, toolName, toolCallId, isError, content }) => ({
      role, toolName, toolCallId, isError,
      content: typeof content === "string" ? content : content.filter((c) => c.type !== "image"),
    }));
    if (opts.out) await writeFile(opts.out, JSON.stringify({ task: opts.task, record, verdict, trace }, null, 2) + "\n", { flag: "wx" });
    console.log(JSON.stringify({ verdict, run_id: record.run_id, result: record.result, evidence: record.completion_evidence, duration_ms: record.duration_ms }, null, 2));
    return verdict.ok ? 0 : 1;
  }
  await adb("shell", "am", "broadcast", "-a", "ai.mobileagent.DEBUG_STOP_AGENT_TASK", "-p", "ai.mobileagent");
  throw new Error("Timed out; requested stop. Check permission, model, or approval state.");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { process.exitCode = await main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
