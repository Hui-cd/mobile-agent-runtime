import assert from "node:assert/strict";
import { test } from "node:test";
const smokeModulePath = "../scripts/android-device-smoke.mjs";
const {
  evaluateRunRecord,
  findTerminalCandidate,
  isCandidateRun,
  parseAdbDevices,
  parseRunsJsonl,
  selectDevice,
  CLOCK_PACKAGES,
  COHORT,
  TASK_ID,
} = await import(smokeModulePath);

const PHYSICAL_ENTRY = "0A021FDD4002P9\tdevice usb:336855040X product:beyond1q model:SM_G973F device:beyond1qlte";

test("emulator opt-in preserves the physical gate and selects only emulators", () => {
  const emulator = { serial: "emulator-5554", state: "device" };
  const physical = { serial: "phone", state: "device" };
  assert.equal(selectDevice([emulator]).ok, false);
  assert.equal(selectDevice([physical], { emulator: true }).ok, false);
  assert.equal(selectDevice([physical, emulator], { emulator: true }).device.serial, emulator.serial);
});

test("emulator evidence never passes the default physical gate", () => {
  const cohort = "emulator-clock-smoke-v1";
  const record = successRecord({ environment: "emulator", dev_only: true, benchmark_cohort: cohort });
  assert.equal(evaluateRunRecord(record).ok, false);
  assert.equal(evaluateRunRecord(record, { emulator: true, cohort }).ok, true);
  assert.equal(evaluateRunRecord(successRecord(), { emulator: true, cohort }).ok, false);
});

function successRecord(overrides: Record<string, unknown> = {}) {
  return {
    run_id: "run-1",
    schema_version: 1,
    platform: "android",
    environment: "physical_device",
    dev_only: false,
    task_id: TASK_ID,
    benchmark_cohort: COHORT,
    status: "success",
    target_package: CLOCK_PACKAGES[0],
    completion_evidence: {
      observe_count: 2,
      act_count: 1,
      invoke_count: 0,
      claim_follows_tool_evidence: true,
      last_observation: { current_app: CLOCK_PACKAGES[0], observed_at: "2026-09-18T00:00:00Z", node_count: 42, truncated: false },
      last_tool: { name: "device_observe", is_error: false },
    },
    ...overrides,
  };
}

test("parseAdbDevices skips header and parses long fields", () => {
  const devices = parseAdbDevices(`List of devices attached\n${PHYSICAL_ENTRY}\n\n`);
  assert.equal(devices.length, 1);
  assert.deepEqual(devices[0], {
    serial: "0A021FDD4002P9",
    state: "device",
    model: "SM_G973F",
    product: "beyond1q",
    device: "beyond1qlte",
  });
});

test("selectDevice: no devices", () => {
  const verdict = selectDevice([]);
  assert.equal(verdict.ok, false);
  assert.equal(verdict.code, "NO_DEVICES");
  assert.match(verdict.message, /no devices connected/);
});

test("selectDevice: unauthorized device is rejected with actionable message", () => {
  const verdict = selectDevice([{ serial: "abc123", state: "unauthorized" }]);
  assert.equal(verdict.ok, false);
  assert.equal(verdict.code, "DEVICE_UNAUTHORIZED");
  assert.match(verdict.message, /RSA/);
});

test("selectDevice: offline device is rejected", () => {
  const verdict = selectDevice([{ serial: "abc123", state: "offline" }]);
  assert.equal(verdict.ok, false);
  assert.equal(verdict.code, "DEVICE_OFFLINE");
});

test("selectDevice: emulator is rejected even when state is device", () => {
  const emulator = parseAdbDevices("emulator-5554\tdevice usb:emulator product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emulator64a")[0];
  const verdict = selectDevice([emulator]);
  assert.equal(verdict.ok, false);
  assert.equal(verdict.code, "EMULATOR_REJECTED");
});

test("selectDevice: single authorized physical device is accepted", () => {
  const device = parseAdbDevices(PHYSICAL_ENTRY)[0];
  const verdict = selectDevice([device]);
  assert.equal(verdict.ok, true);
  assert.equal(verdict.device.serial, "0A021FDD4002P9");
});

test("selectDevice: an attached emulator does not hide the single physical device", () => {
  const physical = parseAdbDevices(PHYSICAL_ENTRY)[0];
  const emulator = parseAdbDevices("emulator-5554\tdevice product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emulator64a")[0];
  const verdict = selectDevice([emulator, physical]);
  assert.equal(verdict.ok, true);
  assert.equal(verdict.device.serial, physical.serial);
});

test("selectDevice: multiple physical devices require --serial", () => {
  const other = { serial: "XYZ", state: "device", model: "SM_G998B", product: "p3", device: "p3q" };
  const verdict = selectDevice([parseAdbDevices(PHYSICAL_ENTRY)[0], other]);
  assert.equal(verdict.ok, false);
  assert.equal(verdict.code, "MULTIPLE_DEVICES");

  const picked = selectDevice([parseAdbDevices(PHYSICAL_ENTRY)[0], other], { serial: "XYZ" });
  assert.equal(picked.ok, true);
  assert.equal(picked.device.serial, "XYZ");
});

test("selectDevice: --serial missing or unauthorized is rejected", () => {
  const missing = selectDevice([], { serial: "nope" });
  assert.equal(missing.code, "DEVICE_NOT_FOUND");

  const unauthorized = selectDevice([{ serial: "abc", state: "unauthorized" }], { serial: "abc" });
  assert.equal(unauthorized.ok, false);
  assert.equal(unauthorized.code, "DEVICE_UNAUTHORIZED");
});

test("evaluateRunRecord: full success record passes", () => {
  assert.deepEqual(evaluateRunRecord(successRecord()), { ok: true });
});

test("evaluateRunRecord: success with AOSP clock package also passes", () => {
  const record = successRecord({
    target_package: CLOCK_PACKAGES[1],
    completion_evidence: {
      ...successRecord().completion_evidence,
      last_observation: { current_app: CLOCK_PACKAGES[1] },
    },
  });
  assert.deepEqual(evaluateRunRecord(record), { ok: true });
});

test("evaluateRunRecord: model replied but completion evidence missing or negative", () => {
  const missing = evaluateRunRecord(successRecord({ completion_evidence: undefined }));
  assert.equal(missing.ok, false);
  assert.ok(missing.reasons.some((r: string) => r.includes("completion_evidence is missing")));

  const negative = evaluateRunRecord(
    successRecord({
      completion_evidence: { ...successRecord().completion_evidence, claim_follows_tool_evidence: false },
    }),
  );
  assert.equal(negative.ok, false);
  assert.ok(negative.reasons.some((r: string) => r.includes("claim_follows_tool_evidence")));
});

test("evaluateRunRecord: wrong target app fails with reason", () => {
  const record = successRecord({
    target_package: "com.android.chrome",
    completion_evidence: {
      ...successRecord().completion_evidence,
      last_observation: { current_app: "com.android.chrome" },
    },
  });
  const verdict = evaluateRunRecord(record);
  assert.equal(verdict.ok, false);
  assert.ok(verdict.reasons.some((r: string) => r.includes("target_package")));
  assert.ok(verdict.reasons.some((r: string) => r.includes("current_app")));
});

test("evaluateRunRecord: non-success terminal status and environment/dev_only gates", () => {
  const failed = evaluateRunRecord(successRecord({ status: "failed" }));
  assert.equal(failed.ok, false);
  assert.ok(failed.reasons.some((r: string) => r.includes('"failed"')));

  const emulatorRun = evaluateRunRecord(successRecord({ environment: "emulator", dev_only: true }));
  assert.equal(emulatorRun.ok, false);
  assert.ok(emulatorRun.reasons.some((r: string) => r.includes("physical_device")));
  assert.ok(emulatorRun.reasons.some((r: string) => r.includes("dev_only")));

  const wrongCohort = evaluateRunRecord(successRecord({ benchmark_cohort: "other" }));
  assert.equal(wrongCohort.ok, false);
});

test("isCandidateRun matches only new task/cohort records", () => {
  const baseline = new Set(["old-run"]);
  const ours = successRecord();
  assert.equal(isCandidateRun(ours, baseline), true);
  assert.equal(isCandidateRun({ ...ours, run_id: "old-run" }, baseline), false);
  assert.equal(isCandidateRun({ ...ours, benchmark_cohort: "other" }, baseline), false);
  assert.equal(isCandidateRun({ ...ours, task_id: "L2" }, baseline), false);
});

test("findTerminalCandidate picks the newest terminal record of this run", () => {
  const pending = successRecord({ run_id: "r1", status: "pending" });
  const failed = successRecord({ run_id: "r2", status: "failed", benchmark_cohort: "other" });
  const success = successRecord({ run_id: "r3" });
  const records = [pending, failed, success];
  assert.equal(findTerminalCandidate(records, new Set())?.run_id, "r3");
  assert.equal(findTerminalCandidate(records, new Set(["r3", "r2"])), undefined);
});

test("parseRunsJsonl tolerates blank and partially written lines", () => {
  const records = parseRunsJsonl(`\n{"run_id":"a"}\n{"run_id":  \n{"run_id":"b"}\n`);
  assert.deepEqual(records, [{ run_id: "a" }, { run_id: "b" }]);
});
