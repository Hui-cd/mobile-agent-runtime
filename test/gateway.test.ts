import assert from "node:assert/strict";
import { test } from "node:test";
import { AgentGateway } from "../src/gateway/agent-gateway.js";
import { MobileToolRegistry } from "../src/gateway/mobile-tools.js";
import { OpenAIResponsesProvider } from "../src/gateway/openai-responses.js";
import type { DeviceAdapter, Observation } from "../src/protocol.js";
import type { AgentProvider, GatewayTool, ProviderTurn, ToolResult } from "../src/gateway/types.js";

const observation: Observation = {
  observed_at: "2026-08-06T00:00:00.000Z",
  current_app: "com.example",
  keyboard_visible: false,
  capabilities: {
    platform: "android",
    adapter: "fake",
    connected: true,
    features: {
      screenshot: true,
      ui_tree: true,
      semantic_ui_control: true,
      notifications: false,
      unicode_input: false,
      invoke: [],
    },
    limitations: [],
  },
};

const fakeAdapter: DeviceAdapter = {
  observe: async () => observation,
  act: async (request) => ({ ok: true, operation: request.action, elapsed_ms: 1, observation, stability: { stable: true, waited_ms: 1 } }),
  invoke: async (request) => ({ ok: true, operation: request.capability, elapsed_ms: 1, observation, stability: { stable: true, waited_ms: 1 } }),
};

class ObserveThenAnswer implements AgentProvider<number> {
  start(_prompt: string, tools: GatewayTool[]): Promise<ProviderTurn<number>> {
    assert.deepEqual(tools.map((tool) => tool.name), ["device_observe", "device_act", "device_invoke"]);
    return Promise.resolve({ text: "", toolCalls: [{ id: "call-1", name: "device_observe", arguments: "{}" }], state: 1 });
  }

  continue(state: number, results: ToolResult[]): Promise<ProviderTurn<number>> {
    assert.equal(state, 1);
    assert.equal(JSON.parse(results[0]!.output).current_app, "com.example");
    return Promise.resolve({ text: "done", toolCalls: [], state: 2 });
  }
}

test("agent gateway executes a provider-neutral tool loop", async () => {
  const gateway = new AgentGateway(new ObserveThenAnswer(), new MobileToolRegistry(fakeAdapter));
  const result = await gateway.run("What is open?");
  assert.equal(result.output, "done");
  assert.equal(result.steps, 1);
  assert.equal(result.trace[0]?.tool, "device_observe");
});

test("gateway blocks device mutation without request authorization", async () => {
  let returned: Record<string, unknown> | undefined;
  const provider: AgentProvider<number> = {
    start: async () => ({ text: "", toolCalls: [{ id: "call-1", name: "device_act", arguments: '{"action":"home"}' }], state: 1 }),
    continue: async (_state, results) => {
      returned = JSON.parse(results[0]!.output) as Record<string, unknown>;
      return { text: "blocked", toolCalls: [], state: 2 };
    },
  };
  const gateway = new AgentGateway(provider, new MobileToolRegistry(fakeAdapter));
  await gateway.run("Go home");
  assert.equal(returned?.error, "DEVICE_ACTIONS_NOT_AUTHORIZED");
});

test("OpenAI Responses provider maps function calls and outputs", async () => {
  const requests: Record<string, unknown>[] = [];
  const fetcher: typeof fetch = async (_url, init) => {
    const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
    requests.push(body);
    if (requests.length === 1) {
      return new Response(JSON.stringify({
        id: "resp-1",
        output: [{ type: "function_call", call_id: "call-1", name: "device_observe", arguments: "{}" }],
      }), { status: 200 });
    }
    return new Response(JSON.stringify({
      id: "resp-2",
      output: [{ type: "message", content: [{ type: "output_text", text: "finished" }] }],
    }), { status: 200 });
  };
  const provider = new OpenAIResponsesProvider({ apiKey: "test", model: "test-model", fetch: fetcher });
  const tools = new MobileToolRegistry(fakeAdapter).list();
  const first = await provider.start("Inspect", tools);
  assert.equal(first.toolCalls[0]?.name, "device_observe");
  const second = await provider.continue(first.state, [{ callId: "call-1", name: "device_observe", output: "{}" }], tools);
  assert.equal(second.text, "finished");
  assert.equal(requests[1]?.previous_response_id, "resp-1");
});
