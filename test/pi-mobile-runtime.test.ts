import assert from "node:assert/strict";
import { test } from "node:test";
import { PiMobileAgentRuntime, type MobileNativeCall } from "../src/pi/mobile-agent-runtime.js";

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
