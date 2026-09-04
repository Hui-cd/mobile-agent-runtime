import fs from "node:fs";

const input = process.argv[2];
if (!input) {
  console.error("usage: node scripts/summarize-benchmark.mjs <runs.jsonl>");
  process.exit(2);
}

const lines = fs.readFileSync(input, "utf8").split(/\r?\n/).filter(Boolean);
const runs = lines.map((line, index) => {
  let run;
  try { run = JSON.parse(line); }
  catch { throw new Error(`line ${index + 1}: invalid JSON`); }
  run = normalizeLegacyEvidence(run);
  const errors = validate(run);
  if (errors.length) throw new Error(`line ${index + 1}: ${errors.join(", ")}`);
  return run;
});

const groups = new Map();
for (const run of runs) {
  const adjudicationFailure = postAdjudicationFailure(run);
  const effectiveStatus = run.status === "success" && adjudicationFailure ? "failed" : run.status;
  const endpointHost = run.model_endpoint_host ?? "legacy-unknown";
  const benchmarkCohort = run.benchmark_cohort ?? "legacy-unknown";
  const key = [run.platform, run.backend, run.task_id, run.environment, endpointHost, run.model, benchmarkCohort].join("/");
  const group = groups.get(key) ?? {
    platform: run.platform,
    backend: run.backend,
    task_id: run.task_id,
    environment: run.environment,
    model_endpoint_host: endpointHost,
    model: run.model,
    benchmark_cohort: benchmarkCohort,
    total: 0,
    success: 0,
    failed: 0,
    cancelled: 0,
    completed_unverified: 0,
    unsupported: 0,
    product_eligible: 0,
    product_eligible_success: 0,
    invalidated_successes: 0,
    legacy_evidence_encodings: 0,
    duration_ms: [],
    foreground_interrupt_ms: [],
    agent_turns: [],
    model_calls: [],
    tool_calls: [],
    manual_takeovers: 0,
    observation_failures: 0,
    action_failures: 0,
    crashes: 0,
    login_applicable_runs: 0,
    login_state_observed_runs: 0,
    login_state_unknown_runs: 0,
    login_not_applicable_runs: 0,
    login_persistence_verified_runs: 0,
    login_losses: 0,
    permission_losses: 0,
  };
  group.total += 1;
  group[effectiveStatus] += 1;
  if (run.status === "success" && effectiveStatus === "failed") group.invalidated_successes += 1;
  group.legacy_evidence_encodings += run._legacy_evidence_encodings ?? 0;
  if (!run.dev_only) {
    group.product_eligible += 1;
    if (effectiveStatus === "success") group.product_eligible_success += 1;
  }
  group.duration_ms.push(run.duration_ms);
  group.foreground_interrupt_ms.push(run.foreground_interrupt_ms);
  group.agent_turns.push(run.agent_turns);
  group.model_calls.push(run.model_calls);
  group.tool_calls.push(run.tool_calls);
  group.manual_takeovers += run.manual_takeovers;
  group.observation_failures += run.observation_failures;
  group.action_failures += run.action_failures;
  if (run.crash) group.crashes += 1;
  const loginStateBefore = run.login_state_before ?? "unknown";
  if (loginStateBefore === "not_applicable") group.login_not_applicable_runs += 1;
  else {
    group.login_applicable_runs += 1;
    if (["signed_in", "signed_out"].includes(loginStateBefore)) group.login_state_observed_runs += 1;
    else group.login_state_unknown_runs += 1;
  }
  if (loginStateBefore === "signed_in" && typeof run.login_lost === "boolean") {
    group.login_persistence_verified_runs += 1;
  }
  if (run.login_lost === true) group.login_losses += 1;
  if (run.permission_lost === true) group.permission_losses += 1;
  groups.set(key, group);
}

const summary = [...groups.values()].map((group) => ({
  ...group,
  success_rate: group.total ? group.success / group.total : 0,
  product_success_rate: group.product_eligible ? group.product_eligible_success / group.product_eligible : null,
  duration_ms: stats(group.duration_ms),
  foreground_interrupt_ms: stats(group.foreground_interrupt_ms),
  agent_turns: statsWithTotal(group.agent_turns),
  model_calls: statsWithTotal(group.model_calls),
  tool_calls: statsWithTotal(group.tool_calls),
  meets_v0_repeat_count: group.total >= 10,
  meets_v0_success_rate: group.total >= 10 && group.success / group.total >= 0.8,
  meets_v0_product_gate: group.product_eligible >= 10 && group.product_eligible_success / group.product_eligible >= 0.8,
}));
console.log(JSON.stringify({ schema_version: 1, total_runs: runs.length, groups: summary }, null, 2));

function validate(run) {
  const errors = [];
  const required = [
    "schema_version", "run_id", "started_at", "platform", "environment", "dev_only", "runtime_version", "agent_runtime", "model", "backend",
    "task_id", "attempt", "status", "duration_ms", "agent_turns", "model_calls", "tool_calls",
    "manual_takeovers", "foreground_interrupt_ms", "crash", "observation_failures", "action_failures",
    "result", "evidence",
  ];
  for (const key of required) if (!(key in run)) errors.push(`missing ${key}`);
  if (run.schema_version !== 1) errors.push("schema_version must be 1");
  if (!['android', 'ios'].includes(run.platform)) errors.push("invalid platform");
  if (!['success', 'failed', 'cancelled', 'completed_unverified', 'unsupported'].includes(run.status)) errors.push("invalid status");
  const loginStates = ["signed_in", "signed_out", "not_applicable", "unknown"];
  const hasLoginBefore = "login_state_before" in run;
  const hasLoginAfter = "login_state_after" in run;
  if (hasLoginBefore !== hasLoginAfter) errors.push("login state before/after must be recorded together");
  if (hasLoginBefore && !loginStates.includes(run.login_state_before)) errors.push("invalid login_state_before");
  if (hasLoginAfter && !loginStates.includes(run.login_state_after)) errors.push("invalid login_state_after");
  const evidenceShowsSignedOut = Array.isArray(run.evidence) &&
    run.evidence.some((item) => item?.login_state === "signed_out");
  if (hasLoginBefore && run.login_lost === true &&
      !(run.login_state_before === "signed_in" &&
        (run.login_state_after === "signed_out" || evidenceShowsSignedOut))) {
    errors.push("login_lost=true requires signed_in start and observed signed_out evidence");
  }
  if (hasLoginBefore && run.login_lost === false &&
      !(run.login_state_before === "signed_in" && run.login_state_after === "signed_in")) {
    errors.push("login_lost=false requires signed_in persistence");
  }
  for (const key of ["attempt", "duration_ms", "agent_turns", "model_calls", "tool_calls", "manual_takeovers", "foreground_interrupt_ms", "observation_failures", "action_failures"]) {
    if (!Number.isInteger(run[key]) || run[key] < (key === "attempt" ? 1 : 0)) errors.push(`invalid ${key}`);
  }
  if (!Array.isArray(run.evidence)) errors.push("evidence must be array");
  else for (const [index, item] of run.evidence.entries()) {
    if (!item || typeof item !== "object" || Array.isArray(item)) errors.push(`evidence[${index}] must be object`);
    else {
      if (!/^[a-f0-9]{64}$/.test(item.result_sha256 ?? "")) errors.push(`evidence[${index}] invalid result_sha256`);
      if ("login_state" in item && !loginStates.includes(item.login_state)) errors.push(`evidence[${index}] invalid login_state`);
    }
  }
  return errors;
}

function normalizeLegacyEvidence(run) {
  if (!Array.isArray(run?.evidence)) return run;
  let legacyCount = 0;
  const evidence = run.evidence.map((item) => {
    if (typeof item !== "string") return item;
    try {
      const parsed = JSON.parse(item);
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return item;
      legacyCount += 1;
      return parsed;
    } catch {
      return item;
    }
  });
  return legacyCount ? { ...run, evidence, _legacy_evidence_encodings: legacyCount } : run;
}

function postAdjudicationFailure(run) {
  if (run.task_id !== "C1") return null;
  if ((run.adjudicator_version ?? 0) < 2) return "C1_ADJUDICATOR_OUTDATED";
  const evidence = Array.isArray(run.evidence) ? run.evidence : [];
  const successfulActions = new Set(evidence
    .filter((item) => item?.tool === "device_act" && item.is_error === false)
    .map((item) => item.action));
  const scrolled = evidence.filter((item) =>
    item?.tool === "device_act" && item.is_error === false && ["scroll", "swipe"].includes(item.action));
  const finalScroll = scrolled.at(-1);
  const invokeSearch = evidence.some((item) =>
    item?.tool === "device_invoke" && item.is_error === false && item.capability === "open_url" &&
    item.search_query_present === true);
  const summary = run.result?.detail?.summary;
  const reportsReadFailure = typeof summary === "string" &&
    ["no internet", "未能读取", "无法读取", "无法加载", "未加载", "连接失败"]
      .some((token) => summary.toLowerCase().includes(token));
  if (!successfulActions.has("click") || !(successfulActions.has("input") || invokeSearch)) return "C1_SEARCH_EVIDENCE_MISSING";
  if (!finalScroll) return "C1_SCROLL_MISSING";
  if (!Number.isInteger(finalScroll.visible_text_count) || finalScroll.visible_text_count < 8) return "C1_DETAIL_CONTENT_MISSING";
  if (finalScroll.network_error_visible === true || reportsReadFailure) return "C1_DETAIL_NETWORK_ERROR";
  return null;
}

function stats(values) {
  if (!values.length) return { min: 0, median: 0, p95: 0, max: 0 };
  const sorted = [...values].sort((a, b) => a - b);
  return {
    min: sorted[0],
    median: percentile(sorted, 0.5),
    p95: percentile(sorted, 0.95),
    max: sorted.at(-1),
  };
}

function statsWithTotal(values) {
  return { ...stats(values), total: values.reduce((sum, value) => sum + value, 0) };
}

function percentile(sorted, quantile) {
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * quantile) - 1)];
}
