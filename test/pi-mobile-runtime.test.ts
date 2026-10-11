import assert from "node:assert/strict";
import { test } from "node:test";
import { PiMobileAgentRuntime, collectCompletionEvidence, type MobileNativeCall } from "../src/pi/mobile-agent-runtime.js";

test("benchmark formatting follows the current goal, never a restored old benchmark", async () => {
  const ids: unknown[] = [];
  const runtime = new PiMobileAgentRuntime(async (method, params) => {
    if (method === "model_complete") {
      ids.push(params.benchmark_task_id);
      return { choices: [{ message: { content: "已返回结果。" }, finish_reason: "stop" }] };
    }
    return { accepted: true };
  });
  const old = await runtime.run({ prompt: "[BENCH:L1] old fixture", platform: "android" });
  const current = await runtime.run({ prompt: "打开时钟", platform: "android", messages: old.messages });
  await runtime.run({ prompt: "[BENCH:C1] new fixture", platform: "android", messages: current.messages });
  assert.deepEqual(ids, ["L1", null, "C1"]);
});

test("a later success without observation cannot bind the claim to an old page", () => {
  const runtimeMessages = [
    { role: "toolResult", toolCallId: "old", toolName: "device_observe", isError: false, timestamp: 1,
      content: [{ type: "text", text: JSON.stringify({ current_app: "old", ui_tree: [] }) }] },
    { role: "toolResult", toolCallId: "new", toolName: "device_act", isError: false, timestamp: 2,
      content: [{ type: "text", text: '{"ok":true}' }] },
    { role: "assistant", content: [{ type: "text", text: "已完成" }] },
  ];
  const evidence = collectCompletionEvidence(runtimeMessages as Parameters<typeof collectCompletionEvidence>[0]);
  assert.equal(evidence.last_observation?.current_app, "old");
  assert.equal(evidence.claim_follows_tool_evidence, false);
});

test("tool screenshots reach the next model request after all pending tool results", async () => {
  let turn = 0;
  let screenshot = 0;
  const runtime = new PiMobileAgentRuntime(async (method, params) => {
    if (method === "model_complete") {
      if (++turn === 1) return { choices: [{ message: { tool_calls: ["first", "last"].map((id) => ({
        id, type: "function", function: { name: "device_observe", arguments: '{"include_screen":true}' },
      })) }, finish_reason: "tool_calls" }] };
      const messages = params.messages as Array<{ role: string; content: unknown }>;
      assert.deepEqual(messages.map((message) => message.role), ["system", "user", "assistant", "tool", "tool", "user"]);
      assert.match(JSON.stringify(messages.at(-1)?.content), /data:image\/jpeg;base64,last/);
      assert.doesNotMatch(JSON.stringify(messages), /base64,first/);
      return { choices: [{ message: { content: "已观察截图。" }, finish_reason: "stop" }] };
    }
    if (method === "tool_execute") return {
      json: JSON.stringify({ current_app: "example", ui_tree: [] }),
      screenshotDataUrl: `data:image/jpeg;base64,${++screenshot === 1 ? "first" : "last"}`,
    };
    return { accepted: true };
  });
  await runtime.run({ prompt: "观察屏幕", platform: "android" });
});

test("unknown target fields are rejected before reaching native actions", async () => {
  let turn = 0;
  let nativeActions = 0;
  const runtime = new PiMobileAgentRuntime(async (method) => {
    if (method === "tool_execute") { nativeActions++; return { json: "{}" }; }
    if (method === "model_complete") return ++turn === 1
      ? { choices: [{ message: { tool_calls: [{ id: "bad", type: "function", function: {
        name: "device_act", arguments: '{"action":"click","target":{"label":"guess"}}',
      } }] }, finish_reason: "tool_calls" }] }
      : { choices: [{ message: { content: "参数无效。" }, finish_reason: "stop" }] };
    return { accepted: true };
  });
  const result = await runtime.run({ prompt: "点击", platform: "android" });
  assert.equal(nativeActions, 0);
  assert.equal(result.evidence.last_tool?.is_error, true);
});

test("Pi mobile runtime owns the real model-tool-model loop and returns resumable state", async () => {
  const calls: Array<{ method: string; params: Record<string, unknown> }> = [];
  let modelTurn = 0;
  const nativeCall: MobileNativeCall = async (method, params) => {
    calls.push({ method, params });
    if (method === "model_complete") {
      modelTurn += 1;
      return modelTurn === 1
        ? {
            choices: [{ message: { content: null, tool_calls: [{
              id: "observe-1", type: "function",
              function: { name: "device_observe", arguments: "{\"include_screen\":false}" },
            }] }, finish_reason: "tool_calls" }],
            usage: { prompt_tokens: 10, completion_tokens: 3, total_tokens: 13 },
          }
        : { choices: [{ message: { content: "当前是 Android。" }, finish_reason: "stop" }] };
    }
    if (method === "tool_execute") {
      assert.deepEqual(params, { name: "device_observe", arguments: { include_screen: false } });
      return { json: JSON.stringify({ platform: "android" }) };
    }
    return { accepted: true };
  };

  const result = await new PiMobileAgentRuntime(nativeCall).run({
    prompt: "观察设备",
    platform: "android",
  });

  assert.equal(result.finalText, "当前是 Android。");
  assert.deepEqual(result.messages.map((message) => message.role), ["user", "assistant", "toolResult", "assistant"]);
  assert.equal(calls.filter((call) => call.method === "model_complete").length, 2);
  assert.equal(calls.filter((call) => call.method === "tool_execute").length, 1);
  assert.equal(result.eventTypes.at(-1), "agent_end");

  const firstModelRequest = calls.find((call) => call.method === "model_complete")?.params;
  assert.equal((firstModelRequest?.tools as unknown[]).length, 3);
  assert.equal(((firstModelRequest?.messages as Array<{ role: string }>)[0])?.role, "system");
});

test("Pi mobile runtime restores an existing transcript before the next prompt", async () => {
  const first = new PiMobileAgentRuntime(async (method) => {
    if (method === "model_complete") return { choices: [{ message: { content: "第一轮" }, finish_reason: "stop" }] };
    return { accepted: true };
  });
  const initial = await first.run({ prompt: "一", platform: "ios" });
  let roles: string[] = [];
  const second = new PiMobileAgentRuntime(async (method, params) => {
    if (method === "model_complete") {
      roles = (params.messages as Array<{ role: string }>).map((message) => message.role);
      return { choices: [{ message: { content: "第二轮" }, finish_reason: "stop" }] };
    }
    return { accepted: true };
  });
  const resumed = await second.run({ prompt: "二", platform: "ios", messages: initial.messages });

  assert.deepEqual(roles, ["system", "user", "assistant", "user"]);
  assert.equal(resumed.messages.length, 4);
  assert.equal(resumed.finalText, "第二轮");
});

test("Pi mobile runtime does not reuse completion evidence from restored history", async () => {
  let firstTurn = 0;
  const first = new PiMobileAgentRuntime(async (method) => {
    if (method === "model_complete") {
      firstTurn += 1;
      if (firstTurn === 1) {
        return { choices: [{ message: { content: null, tool_calls: [{
          id: "observe-old", type: "function",
          function: { name: "device_observe", arguments: "{}" },
        }] }, finish_reason: "tool_calls" }] };
      }
      return { choices: [{ message: { content: "旧任务完成。" }, finish_reason: "stop" }] };
    }
    if (method === "tool_execute") {
      return { json: JSON.stringify({
        observed_at: 1726663200000,
        current_app: "com.example.old",
        ui_tree: [],
        ui_tree_truncated: false,
      }) };
    }
    return { accepted: true };
  });
  const initial = await first.run({ prompt: "旧任务", platform: "android" });
  assert.equal(initial.evidence.observe_count, 1);

  const second = new PiMobileAgentRuntime(async (method) => {
    if (method === "model_complete") return { choices: [{ message: { content: "没有调用工具。" }, finish_reason: "stop" }] };
    return { accepted: true };
  });
  const resumed = await second.run({ prompt: "新任务", platform: "android", messages: initial.messages });

  assert.equal(resumed.evidence.observe_count, 0);
  assert.equal(resumed.evidence.last_observation, null);
  assert.equal(resumed.evidence.claim_follows_tool_evidence, false);
});

test("Pi mobile runtime cancellation ends the active Pi run", async () => {
  let release!: (value: unknown) => void;
  const pending = new Promise<unknown>((resolve) => { release = resolve; });
  const runtime = new PiMobileAgentRuntime(async (method) => {
    if (method === "model_complete") return pending;
    return { accepted: true };
  });
  const run = runtime.run({ prompt: "等待", platform: "android" });
  await new Promise((resolve) => setTimeout(resolve, 0));
  runtime.cancel();
  release({ choices: [{ message: { content: "不应成功" }, finish_reason: "stop" }] });

  await assert.rejects(run, /abort/i);
});

test("Pi mobile runtime binds the final claim to the last on-device observation", async () => {
  let modelTurn = 0;
  const runtime = new PiMobileAgentRuntime(async (method) => {
    if (method === "model_complete") {
      modelTurn += 1;
      if (modelTurn === 1) {
        return { choices: [{ message: { content: null, tool_calls: [{
          id: "act-1", type: "function",
          function: { name: "device_act", arguments: "{\"action\":\"click\",\"target\":{\"text\":\"闹钟\"}}" },
        }] }, finish_reason: "tool_calls" }] };
      }
      if (modelTurn === 2) {
        return { choices: [{ message: { content: null, tool_calls: [{
          id: "observe-1", type: "function",
          function: { name: "device_observe", arguments: "{}" },
        }] }, finish_reason: "tool_calls" }] };
      }
      return { choices: [{ message: { content: "已完成。" }, finish_reason: "stop" }] };
    }
    if (method === "tool_execute") {
      return modelTurn === 1
        ? { json: JSON.stringify({ ok: true }) }
        : { json: JSON.stringify({
            observed_at: 1726663200000,
            current_app: "com.google.android.deskclock",
            ui_tree: [{ index: 1 }, { index: 2 }],
            ui_tree_truncated: false,
          }) };
    }
    return { accepted: true };
  });

  const result = await runtime.run({ prompt: "打开时钟", platform: "android" });
  assert.deepEqual(result.evidence, {
    observe_count: 1,
    act_count: 1,
    invoke_count: 0,
    last_observation: {
      current_app: "com.google.android.deskclock",
      observed_at: 1726663200000,
      node_count: 2,
      truncated: false,
    },
    last_tool: { name: "device_observe", is_error: false },
    claim_follows_tool_evidence: true,
  });
});

test("Pi mobile runtime keeps the observation returned by an invoke", async () => {
  let modelTurn = 0;
  const runtime = new PiMobileAgentRuntime(async (method) => {
    if (method === "model_complete") {
      modelTurn += 1;
      if (modelTurn === 1) {
        return { choices: [{ message: { content: null, tool_calls: [{
          id: "invoke-1", type: "function",
          function: { name: "device_invoke", arguments: "{\"capability\":\"open_app\",\"params\":{\"app\":\"时钟\"}}" },
        }] }, finish_reason: "tool_calls" }] };
      }
      return { choices: [{ message: { content: "时钟已打开。" }, finish_reason: "stop" }] };
    }
    if (method === "tool_execute") {
      return { json: JSON.stringify({
        observed_at: 1726663200000,
        current_app: "com.google.android.deskclock",
        ui_tree: [{ index: 1 }],
        ui_tree_truncated: false,
      }) };
    }
    return { accepted: true };
  });

  const result = await runtime.run({ prompt: "打开时钟", platform: "android" });
  assert.equal(result.evidence.invoke_count, 1);
  assert.equal(result.evidence.observe_count, 0);
  assert.equal(result.evidence.last_observation?.current_app, "com.google.android.deskclock");
  assert.equal(result.evidence.claim_follows_tool_evidence, true);
});

test("Pi mobile runtime flags final claims that lack tool evidence", async () => {
  const runtime = new PiMobileAgentRuntime(async (method) => {
    if (method === "model_complete") return { choices: [{ message: { content: "直接回答。" }, finish_reason: "stop" }] };
    return { accepted: true };
  });
  const result = await runtime.run({ prompt: "你好", platform: "android" });
  assert.equal(result.evidence.observe_count, 0);
  assert.equal(result.evidence.last_observation, null);
  assert.equal(result.evidence.claim_follows_tool_evidence, false);
});

test("Pi mobile runtime counts failed observations without breaking the evidence chain", async () => {
  let modelTurn = 0;
  const runtime = new PiMobileAgentRuntime(async (method) => {
    if (method === "model_complete") {
      modelTurn += 1;
      if (modelTurn === 1) {
        return { choices: [{ message: { content: null, tool_calls: [{
          id: "observe-1", type: "function",
          function: { name: "device_observe", arguments: "{}" },
        }] }, finish_reason: "tool_calls" }] };
      }
      return { choices: [{ message: { content: "屏幕不可用。" }, finish_reason: "stop" }] };
    }
    if (method === "tool_execute") return { json: JSON.stringify({ error: "SCREEN_NOT_INTERACTIVE" }), isError: true };
    return { accepted: true };
  });
  const result = await runtime.run({ prompt: "观察", platform: "android" });
  assert.equal(result.evidence.observe_count, 1);
  assert.equal(result.evidence.last_tool?.is_error, true);
  assert.equal(result.evidence.claim_follows_tool_evidence, false);
});

test("Pi mobile runtime enforces its model-turn limit", async () => {
  const runtime = new PiMobileAgentRuntime(async (method) => {
    if (method === "model_complete") {
      return { choices: [{ message: { content: null, tool_calls: [{
        id: crypto.randomUUID(), type: "function",
        function: { name: "device_observe", arguments: "{}" },
      }] }, finish_reason: "tool_calls" }] };
    }
    if (method === "tool_execute") return { json: "{}" };
    return { accepted: true };
  });

  await assert.rejects(runtime.run({ prompt: "循环", platform: "android", maxTurns: 2 }), /STEP_LIMIT/);
});

test("Pi core blocks invalid native-tool arguments before execution", async () => {
  let toolExecutions = 0;
  let modelTurn = 0;
  const runtime = new PiMobileAgentRuntime(async (method, params) => {
    if (method === "tool_execute") {
      toolExecutions += 1;
      return { json: "{}" };
    }
    if (method === "model_complete") {
      modelTurn += 1;
      if (modelTurn === 1) {
        return { choices: [{ message: { content: null, tool_calls: [{
          id: "bad-1", type: "function",
          function: { name: "device_observe", arguments: "{\"include_screen\":\"yes\"}" },
        }] }, finish_reason: "tool_calls" }] };
      }
      const messages = params.messages as Array<{ role: string; content?: string }>;
      assert.match(messages.findLast((message) => message.role === "tool")?.content ?? "", /Validation failed/);
      return { choices: [{ message: { content: "参数已拒绝" }, finish_reason: "stop" }] };
    }
    return { accepted: true };
  });

  const result = await runtime.run({ prompt: "无效参数", platform: "ios" });
  assert.equal(result.finalText, "参数已拒绝");
  assert.equal(toolExecutions, 0);
  const failedToolResult = result.messages.find((message) => message.role === "toolResult");
  assert.equal(failedToolResult?.role === "toolResult" && failedToolResult.isError, true);
});
