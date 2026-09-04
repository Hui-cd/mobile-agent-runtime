import { Agent, type AgentEvent, type AgentTool, type StreamFn } from "@mariozechner/pi-agent-core";
import {
  createAssistantMessageEventStream,
  type AssistantMessage,
  type Model,
} from "@mariozechner/pi-ai";
import { Type } from "typebox";

const fixtureModel: Model<"mobile-fixture"> = {
  id: "mobile-fixture",
  name: "Mobile fixture",
  api: "mobile-fixture",
  provider: "fixture",
  baseUrl: "",
  reasoning: false,
  input: ["text"],
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
  contextWindow: 4096,
  maxTokens: 512,
};

const emptyUsage = {
  input: 0,
  output: 0,
  cacheRead: 0,
  cacheWrite: 0,
  totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

export interface PiMobileCoreFixtureResult {
  finalText: string;
  observedPlatform: string;
  eventTypes: AgentEvent["type"][];
  messageRoles: string[];
}

/**
 * Executes a deterministic Pi tool loop without a network provider.
 *
 * Mobile embedders can replace `fixtureStream` with a native-backed streamFn and
 * replace `observeTool` with the device bridge while preserving this lifecycle.
 */
export async function runPiMobileCoreFixture(
  nativeObserve: () => Promise<{ platform: string }> = async () => ({ platform: "fixture" }),
): Promise<PiMobileCoreFixtureResult> {
  let observedPlatform = "";
  const observeTool: AgentTool = {
    name: "device_observe",
    label: "Observe device",
    description: "Return the current mobile platform.",
    parameters: Type.Object({}),
    execute: async () => {
      const observation = await nativeObserve();
      observedPlatform = observation.platform;
      return {
        content: [{ type: "text", text: JSON.stringify(observation) }],
        details: observation,
      };
    },
  };

  const agent = new Agent({
    initialState: {
      systemPrompt: "Run the deterministic mobile fixture.",
      model: fixtureModel,
      thinkingLevel: "off",
      tools: [observeTool],
      messages: [],
    },
    streamFn: fixtureStream,
    toolExecution: "sequential",
  });

  const eventTypes: AgentEvent["type"][] = [];
  agent.subscribe((event) => {
    eventTypes.push(event.type);
  });

  await agent.prompt("Observe this device and report the platform.");

  const finalMessage = [...agent.state.messages]
    .reverse()
    .find((message): message is AssistantMessage => message.role === "assistant");
  const finalText = finalMessage?.content
    .filter((content) => content.type === "text")
    .map((content) => content.text)
    .join("") ?? "";

  return {
    finalText,
    observedPlatform,
    eventTypes,
    messageRoles: agent.state.messages.map((message) => message.role),
  };
}

const fixtureStream: StreamFn = (_model, context, _options) => {
  const hasToolResult = context.messages.some((message) => message.role === "toolResult");
  const message: AssistantMessage = hasToolResult
    ? {
        role: "assistant",
        content: [{ type: "text", text: "Pi mobile fixture complete." }],
        api: "mobile-fixture",
        provider: "fixture",
        model: fixtureModel.id,
        usage: emptyUsage,
        stopReason: "stop",
        timestamp: Date.now(),
      }
    : {
        role: "assistant",
        content: [{ type: "toolCall", id: "observe-1", name: "device_observe", arguments: {} }],
        api: "mobile-fixture",
        provider: "fixture",
        model: fixtureModel.id,
        usage: emptyUsage,
        stopReason: "toolUse",
        timestamp: Date.now(),
      };

  const stream = createAssistantMessageEventStream();
  queueMicrotask(() => {
    stream.push({ type: "start", partial: message });
    stream.push({ type: "done", reason: message.stopReason as "stop" | "toolUse", message });
    stream.end(message);
  });
  return stream;
};
