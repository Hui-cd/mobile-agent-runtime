import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { test } from "node:test";

test("benchmark summary validates JSONL and aggregates success and stability metrics", () => {
  const output = execFileSync(
    process.execPath,
    ["scripts/summarize-benchmark.mjs", "test/fixtures/benchmark-runs.jsonl"],
    { encoding: "utf8" },
  );
  const report = JSON.parse(output) as {
    total_runs: number;
    groups: Array<Record<string, unknown>>;
  };

  assert.equal(report.total_runs, 2);
  assert.equal(report.groups[0]?.success_rate, 0.5);
  assert.equal(report.groups[0]?.model_endpoint_host, "legacy-unknown");
  assert.equal(report.groups[0]?.model, "kimi-k3");
  assert.equal(report.groups[0]?.product_success_rate, 0.5);
  assert.equal(report.groups[0]?.permission_losses, 1);
  assert.deepEqual(report.groups[0]?.duration_ms, { min: 1000, median: 1000, p95: 2000, max: 2000 });
  assert.deepEqual(report.groups[0]?.model_calls, { min: 2, median: 2, p95: 3, max: 3, total: 5 });
  assert.equal(report.groups[0]?.manual_takeovers, 1);
  assert.equal(report.groups[0]?.observation_failures, 1);
  assert.equal(report.groups[0]?.meets_v0_repeat_count, false);
  assert.equal(report.groups[0]?.meets_v0_product_gate, false);
});

test("benchmark summary invalidates legacy C1 false positives", () => {
  const output = execFileSync(
    process.execPath,
    ["scripts/summarize-benchmark.mjs", "test/fixtures/benchmark-c1-invalid.jsonl"],
    { encoding: "utf8" },
  );
  const report = JSON.parse(output) as {
    groups: Array<Record<string, unknown>>;
  };

  assert.equal(report.groups[0]?.success, 2);
  assert.equal(report.groups[0]?.failed, 1);
  assert.equal(report.groups[0]?.invalidated_successes, 1);
  assert.equal(report.groups[0]?.success_rate, 2 / 3);
  assert.equal(report.groups[0]?.meets_v0_success_rate, false);
});

test("benchmark summary normalizes legacy string-encoded evidence", () => {
  const output = execFileSync(
    process.execPath,
    ["scripts/summarize-benchmark.mjs", "test/fixtures/benchmark-legacy-evidence.jsonl"],
    { encoding: "utf8" },
  );
  const report = JSON.parse(output) as {
    groups: Array<Record<string, unknown>>;
  };

  assert.equal(report.groups[0]?.success, 1);
  assert.equal(report.groups[0]?.legacy_evidence_encodings, 1);
});

test("benchmark summary never mixes endpoint model or cohort changes", () => {
  const output = execFileSync(
    process.execPath,
    ["scripts/summarize-benchmark.mjs", "test/fixtures/benchmark-provider-groups.jsonl"],
    { encoding: "utf8" },
  );
  const report = JSON.parse(output) as {
    groups: Array<Record<string, unknown>>;
  };

  assert.equal(report.groups.length, 3);
  assert.deepEqual(
    report.groups.map((group) => [group.model_endpoint_host, group.model, group.benchmark_cohort]),
    [
      ["api.moonshot.cn", "kimi-k3", "baseline-a"],
      ["models.example.com", "compatible-model", "baseline-a"],
      ["api.moonshot.cn", "kimi-k3", "smoke-a"],
    ],
  );
});

test("benchmark summary distinguishes login loss from missing login evidence", () => {
  const output = execFileSync(
    process.execPath,
    ["scripts/summarize-benchmark.mjs", "test/fixtures/benchmark-login-states.jsonl"],
    { encoding: "utf8" },
  );
  const report = JSON.parse(output) as { groups: Array<Record<string, unknown>> };
  const group = report.groups[0];

  assert.equal(group?.login_applicable_runs, 4);
  assert.equal(group?.login_state_observed_runs, 3);
  assert.equal(group?.login_state_unknown_runs, 1);
  assert.equal(group?.login_not_applicable_runs, 1);
  assert.equal(group?.login_persistence_verified_runs, 3);
  assert.equal(group?.login_losses, 2);
});
