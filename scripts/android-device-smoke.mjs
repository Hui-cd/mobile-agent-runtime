#!/usr/bin/env node
// L1 physical-device smoke runner (PROJECT_STATUS.md phase 1):
// opens the Clock app on a real Android device via the debug AgentTaskTestReceiver,
// then polls the on-device benchmark store (runs.jsonl via run-as) and adjudicates
// the terminal record. Exit 0 only when every gate in evaluateRunRecord passes.
//
// Usage: node scripts/android-device-smoke.mjs [--serial <id>] [--install] [--timeout <sec>] [--apk <path>]
//
// Pure helpers (parseAdbDevices / selectDevice / evaluateRunRecord / findNewTerminalRun)
// are exported for tests; everything device-specific stays in main().

import { execFile } from "node:child_process";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

export const TASK_ID = "L1";
export const COHORT = "physical-clock-smoke-v1";
export const PROMPT = `[BENCH:${TASK_ID}] [COHORT:${COHORT}] Open the Clock app, observe the current page, and return exactly one JSON object with this structure: {"visible_title":"the visible Clock tab or page title"}.`;
export const AGENT_PACKAGE = "ai.mobileagent";
export const ACCESSIBILITY_SERVICE = `${AGENT_PACKAGE}/.accessibility.MobileAgentAccessibilityService`;
export const CLOCK_PACKAGES = ["com.google.android.deskclock", "com.android.deskclock"];
export const RUNS_PATH = "files/benchmarks/runs.jsonl";
export const DEFAULT_APK = "apps/android/app/build/outputs/apk/debug/app-debug.apk";
export const TERMINAL_STATUSES = new Set(["success", "failed", "cancelled", "unsupported", "completed_unverified"]);

const DEFAULT_TIMEOUT_SEC = 180;
const POLL_INTERVAL_MS = 2_000;

// ---------------------------------------------------------------------------
// Pure functions (unit-tested in test/android-device-smoke.test.ts)
// ---------------------------------------------------------------------------

/** Parse `adb devices -l` output into [{serial, state, model, product, device}]. */
export function parseAdbDevices(output) {
  const devices = [];
  for (const line of output.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("List of devices attached") || trimmed.startsWith("*")) continue;
    const [serial, state, ...rest] = trimmed.split(/\s+/);
    const entry = { serial, state, model: undefined, product: undefined, device: undefined };
    for (const field of rest) {
      const idx = field.indexOf(":");
      if (idx <= 0) continue;
      const key = field.slice(0, idx);
      if (key === "model" || key === "product" || key === "device") entry[key] = field.slice(idx + 1);
    }
    devices.push(entry);
  }
  return devices;
}

/** Heuristic emulator detection: serial prefix or emulated hardware markers. */
export function isEmulatorDevice(device) {
  if (device.serial.startsWith("emulator-")) return true;
  const markers = [device.model, device.product, device.device]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();
  return /emulator|sdk_gphone|generic|goldfish|ranchu/.test(markers);
}

/**
 * Pick the target device. Returns { ok: true, device } or { ok: false, code, message }.
 * Only one authorized physical device is accepted unless --serial disambiguates.
 */
export function selectDevice(devices, { serial, emulator = false } = {}) {
  const fail = (code, message) => ({ ok: false, code, message });
  if (serial) {
    const match = devices.find((d) => d.serial === serial);
    if (!match) return fail("DEVICE_NOT_FOUND", `device "${serial}" not found in \`adb devices\`; check the USB connection`);
    return validateDevice(match, emulator);
  }
  if (devices.length === 0) {
    return fail("NO_DEVICES", "no devices connected; plug in a physical Android device and enable USB debugging");
  }
  const usable = devices.filter((device) => validateDevice(device, emulator).ok);
  if (usable.length === 0) {
    const actionable = devices.find((device) => device.state !== "device") ?? devices[0];
    return validateDevice(actionable, emulator);
  }
  if (usable.length > 1) {
    return fail("MULTIPLE_DEVICES", `multiple physical devices connected (${usable.map((d) => d.serial).join(", ")}); re-run with --serial`);
  }
  return { ok: true, device: usable[0] };
}

function validateDevice(device, emulator = false) {
  if (device.state === "unauthorized") {
    return {
      ok: false,
      code: "DEVICE_UNAUTHORIZED",
      message: `device ${device.serial} is unauthorized; accept the USB debugging RSA prompt on the device (and check "always allow")`,
    };
  }
  if (device.state !== "device") {
    return { ok: false, code: "DEVICE_OFFLINE", message: `device ${device.serial} is ${device.state}; check the USB cable / re-run \`adb reconnect\`` };
  }
  if (emulator && !isEmulatorDevice(device)) {
    return { ok: false, code: "PHYSICAL_DEVICE_REJECTED", message: "--emulator requires an emulator; physical devices use the default gate" };
  }
  if (!emulator && isEmulatorDevice(device)) {
    return { ok: false, code: "EMULATOR_REJECTED", message: `device ${device.serial} looks like an emulator; the L1 smoke gate requires a physical_device` };
  }
  return { ok: true, device };
}

/**
 * Adjudicate one benchmark record against the L1 smoke gate.
 * Returns { ok: true } or { ok: false, reasons: [...] } with concise, non-sensitive reasons.
 */
export function evaluateRunRecord(record, { taskId = TASK_ID, cohort = COHORT, clockPackages = CLOCK_PACKAGES, emulator = false } = {}) {
  const reasons = [];
  const r = record ?? {};
  const clockSet = new Set(clockPackages);

  const expectedEnvironment = emulator ? "emulator" : "physical_device";
  if (r.environment !== expectedEnvironment) reasons.push(`environment is ${JSON.stringify(r.environment)}, expected "${expectedEnvironment}"`);
  if (r.dev_only !== emulator) reasons.push(`dev_only is ${JSON.stringify(r.dev_only)}, expected ${emulator}`);
  if (r.task_id !== taskId) reasons.push(`task_id is ${JSON.stringify(r.task_id)}, expected "${taskId}"`);
  if (r.benchmark_cohort !== cohort) reasons.push(`benchmark_cohort is ${JSON.stringify(r.benchmark_cohort)}, expected "${cohort}"`);
  if (r.status !== "success") reasons.push(`status is ${JSON.stringify(r.status)}, expected "success"`);
  if (!clockSet.has(r.target_package)) reasons.push(`target_package is ${JSON.stringify(r.target_package)}, expected one of ${clockPackages.join(", ")}`);

  const evidence = r.completion_evidence;
  if (!evidence || typeof evidence !== "object") {
    reasons.push("completion_evidence is missing (model replied without verifiable tool evidence)");
  } else {
    if (evidence.claim_follows_tool_evidence !== true) {
      reasons.push(`completion_evidence.claim_follows_tool_evidence is ${JSON.stringify(evidence.claim_follows_tool_evidence)}, expected true`);
    }
    const currentApp = evidence.last_observation?.current_app;
    if (!clockSet.has(currentApp)) {
      reasons.push(`completion_evidence.last_observation.current_app is ${JSON.stringify(currentApp)}, expected one of ${clockPackages.join(", ")}`);
    }
  }

  return reasons.length === 0 ? { ok: true } : { ok: false, reasons };
}

/** Parse JSONL content into records (blank/invalid lines skipped, not fatal). */
export function parseRunsJsonl(content) {
  const records = [];
  for (const line of content.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    try {
      records.push(JSON.parse(trimmed));
    } catch {
      // tolerate partial writes while the store appends
    }
  }
  return records;
}

/** A record counts as "ours" if it appeared after the run started and matches task + cohort. */
export function isCandidateRun(record, baselineRunIds, { taskId = TASK_ID, cohort = COHORT } = {}) {
  return (
    typeof record?.run_id === "string" &&
    !baselineRunIds.has(record.run_id) &&
    record.task_id === taskId &&
    record.benchmark_cohort === cohort
  );
}

/** Newest terminal record matching task+cohort that is not in the baseline, or undefined. */
export function findTerminalCandidate(records, baselineRunIds, options = {}) {
  for (let i = records.length - 1; i >= 0; i -= 1) {
    const record = records[i];
    if (isCandidateRun(record, baselineRunIds, options) && TERMINAL_STATUSES.has(record.status)) {
      return record;
    }
  }
  return undefined;
}

// ---------------------------------------------------------------------------
// Runner
// ---------------------------------------------------------------------------

class SmokeError extends Error {}

function findAdbBinary() {
  const configured = process.env.MOBILE_AGENT_ADB;
  if (configured) return configured;
  const sdkRoots = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT].filter(Boolean);
  for (const sdkRoot of sdkRoots) {
    const candidate = join(sdkRoot, "platform-tools", "adb");
    if (existsSync(candidate)) return candidate;
  }
  const macOsDefault = join(homedir(), "Library", "Android", "sdk", "platform-tools", "adb");
  return existsSync(macOsDefault) ? macOsDefault : "adb";
}

const ADB_BINARY = findAdbBinary();

function adb(args, { serial, timeoutMs = 15_000 } = {}) {
  const fullArgs = serial ? ["-s", serial, ...args] : args;
  return new Promise((resolve, reject) => {
    execFile(ADB_BINARY, fullArgs, { timeout: timeoutMs, maxBuffer: 32 * 1024 * 1024 }, (error, stdout, stderr) => {
      if (error) {
        reject(new SmokeError(`adb ${args.join(" ")} failed: ${(stderr || error.message).trim()}`));
        return;
      }
      resolve(stdout);
    });
  });
}

/** Run a single remote shell command string; returns stdout (non-zero exit → SmokeError). */
async function shell(command, options = {}) {
  return adb(["shell", command], options);
}

function parseArgs(argv) {
  const opts = { serial: undefined, emulator: false, install: false, timeoutSec: DEFAULT_TIMEOUT_SEC, apk: DEFAULT_APK, help: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--serial") opts.serial = requiredValue(argv, ++i, "--serial");
    else if (arg === "--install") opts.install = true;
    else if (arg === "--emulator") opts.emulator = true;
    else if (arg === "--timeout") opts.timeoutSec = Number(requiredValue(argv, ++i, "--timeout"));
    else if (arg === "--apk") opts.apk = requiredValue(argv, ++i, "--apk");
    else if (arg === "--help" || arg === "-h") opts.help = true;
    else throw new SmokeError(`unknown argument: ${arg}`);
  }
  if (!Number.isFinite(opts.timeoutSec) || opts.timeoutSec <= 0) throw new SmokeError("--timeout must be a positive number of seconds");
  return opts;
}

function requiredValue(argv, index, flag) {
  const value = argv[index];
  if (!value || value.startsWith("--")) throw new SmokeError(`${flag} requires a value`);
  return value;
}

const USAGE = `usage: node scripts/android-device-smoke.mjs [--emulator] [--serial <id>] [--install] [--timeout <sec>] [--apk <path>]

Physical-device L1 smoke gate: triggers the fixed "[BENCH:${TASK_ID}] [COHORT:${COHORT}]"
clock task through the debug AgentTaskTestReceiver, then polls runs.jsonl (run-as) and
exits 0 only if the new terminal record passes every product gate.
--emulator uses a separate dev-only cohort; it does not pass physical-device acceptance.`;

async function preflight(opts) {
  await adb(["version"]).catch(() => {
    throw new SmokeError(`adb is not available at ${ADB_BINARY}; install Android platform-tools or set MOBILE_AGENT_ADB`);
  });

  const devices = parseAdbDevices(await adb(["devices", "-l"]));
  const selection = selectDevice(devices, opts);
  if (!selection.ok) throw new SmokeError(selection.message);
  const serial = selection.device.serial;
  console.log(`device: ${serial} (${selection.device.model ?? "unknown model"})`);

  if (opts.install) {
    if (!existsSync(opts.apk)) {
      throw new SmokeError(`debug APK not found at ${opts.apk}; build it first: npm run android:build`);
    }
    console.log(`installing ${opts.apk} ...`);
    await adb(["install", "-r", opts.apk], { serial, timeoutMs: 120_000 });
  }

  const pmPath = await shell(`pm path ${AGENT_PACKAGE}`, { serial });
  if (!pmPath.includes(AGENT_PACKAGE)) {
    throw new SmokeError(`agent package ${AGENT_PACKAGE} is not installed; run: npm run android:smoke -- --install`);
  }

  const power = await shell("dumpsys power", { serial });
  if (!/mWakefulness=Awake/.test(power) && !/mInteractive=true/.test(power)) {
    throw new SmokeError("screen is off or not interactive; wake the device: adb shell input keyevent KEYCODE_WAKEUP");
  }

  const window = await shell("dumpsys window", { serial });
  if (/(?:mKeyguardShowing|mShowingLockscreen|mDreamingLockscreen|isStatusBarKeyguard)=true/.test(window)) {
    throw new SmokeError("device is locked; unlock the screen before running the smoke");
  }
  if (!/(?:mKeyguardShowing|mShowingLockscreen|mDreamingLockscreen|isStatusBarKeyguard)=(?:true|false)/.test(window)) {
    throw new SmokeError("could not verify the lock-screen state from dumpsys window; unlock the device and retry with a supported Android build");
  }

  const services = await shell("settings get secure enabled_accessibility_services", { serial });
  if (!services.split(":").some((service) => service.trim().replace(`${AGENT_PACKAGE}/${AGENT_PACKAGE}.`, `${AGENT_PACKAGE}/.`) === ACCESSIBILITY_SERVICE)) {
    throw new SmokeError(`Mobile Agent Accessibility service is not enabled; enable it in Settings > Accessibility (expected ${ACCESSIBILITY_SERVICE})`);
  }

  const clockInstalled = await shell(`pm list packages ${CLOCK_PACKAGES[0]}; pm list packages ${CLOCK_PACKAGES[1]}`, { serial });
  if (!/^package:/m.test(clockInstalled)) {
    throw new SmokeError(`no clock package found; install Google Clock (${CLOCK_PACKAGES[0]}) or an AOSP clock (${CLOCK_PACKAGES[1]})`);
  }

  return serial;
}

/** Read runs.jsonl via run-as; returns "" when the store file does not exist yet. */
async function readRuns(serial) {
  try {
    return await shell(`run-as ${AGENT_PACKAGE} cat ${RUNS_PATH}`, { serial });
  } catch (error) {
    if (error instanceof SmokeError && /No such file|does not exist/.test(error.message)) return "";
    throw new SmokeError(
      `cannot read ${RUNS_PATH} via run-as on ${serial}; install the debug APK (npm run android:smoke -- --install). ${error.message}`,
    );
  }
}

export async function main(argv = process.argv.slice(2)) {
  let opts;
  try {
    opts = parseArgs(argv);
  } catch (error) {
    console.error(`${error.message}\n\n${USAGE}`);
    return 2;
  }
  if (opts.help) {
    console.log(USAGE);
    return 0;
  }

  try {
    const serial = await preflight(opts);

    const baselineRunIds = new Set(parseRunsJsonl(await readRuns(serial)).map((r) => r.run_id));

    const cohort = opts.emulator ? "emulator-clock-smoke-v1" : COHORT;
    const gate = { cohort, emulator: opts.emulator };
    const promptBase64 = Buffer.from(PROMPT.replace(COHORT, cohort), "utf8").toString("base64");
    console.log(`triggering ${TASK_ID}/${cohort} via AgentTaskTestReceiver ...`);
    await adb(
      ["shell", "am", "broadcast", "-a", `${AGENT_PACKAGE}.DEBUG_RUN_AGENT_TASK`, "-p", AGENT_PACKAGE, "--es", "prompt_base64", promptBase64],
      { serial },
    );

    const deadline = Date.now() + opts.timeoutSec * 1000;
    let lastSeen = "none";
    while (Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
      const records = parseRunsJsonl(await readRuns(serial));
      const newest = records.filter((r) => isCandidateRun(r, baselineRunIds, gate)).at(-1);
      if (newest) lastSeen = `${newest.status} (run ${newest.run_id})`;
      const candidate = findTerminalCandidate(records, baselineRunIds, gate);
      if (!candidate) continue;

      const verdict = evaluateRunRecord(candidate, gate);
      if (verdict.ok) {
        console.log(`PASS: run ${candidate.run_id} status=success target_package=${candidate.target_package} duration_ms=${candidate.duration_ms}`);
        console.log(`result: ${JSON.stringify(candidate.result)}`);
        return 0;
      }
      console.error(`FAIL: run ${candidate.run_id} did not pass the L1 smoke gate:`);
      for (const reason of verdict.reasons) console.error(`  - ${reason}`);
      return 1;
    }

    console.error(`FAIL: timed out after ${opts.timeoutSec}s waiting for a terminal ${TASK_ID}/${COHORT} record; last seen: ${lastSeen}. Check that the app has a working model endpoint/API key and inspect MobileAgentTaskTest/MobileAgent logcat.`);
    return 1;
  } catch (error) {
    console.error(`FAIL: ${error instanceof Error ? error.message : String(error)}`);
    return 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await main();
}
