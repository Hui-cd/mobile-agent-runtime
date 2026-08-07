import { DeviceError } from "../errors.js";
import type { AgentProvider, GatewayTool, ProviderToolCall, ProviderTurn, ToolResult } from "./types.js";

type ChatMessage = Record<string, unknown>;
interface ChatState { messages: ChatMessage[]; assistant: ChatMessage }

export interface OpenAICompatibleChatOptions {
  apiKey: string;
  model: string;
  baseUrl: string;
  fetch?: typeof globalThis.fetch;
}

export class OpenAICompatibleChatProvider implements AgentProvider<ChatState> {
  private readonly baseUrl: string;
  private readonly fetcher: typeof globalThis.fetch;

  constructor(private readonly options: OpenAICompatibleChatOptions) {
    this.baseUrl = options.baseUrl.replace(/\/$/, "");
    this.fetcher = options.fetch ?? globalThis.fetch;
  }

  private providerTools(tools: GatewayTool[]): unknown[] {
    return tools.map((tool) => ({
      type: "function",
      function: { name: tool.name, description: tool.description, parameters: tool.parameters },
    }));
  }

  private async request(messages: ChatMessage[], tools: GatewayTool[]): Promise<ProviderTurn<ChatState>> {
    const response = await this.fetcher(`${this.baseUrl}/chat/completions`, {
      method: "POST",
      headers: { authorization: `Bearer ${this.options.apiKey}`, "content-type": "application/json" },
      body: JSON.stringify({ model: this.options.model, messages, tools: this.providerTools(tools), tool_choice: "auto" }),
    });
    const raw = await response.text();
    let payload: Record<string, unknown>;
    try {
      payload = JSON.parse(raw) as Record<string, unknown>;
    } catch {
      throw new DeviceError("Provider returned non-JSON data", "PROVIDER_BAD_RESPONSE", { status: response.status, body: raw.slice(0, 1_000) });
    }
    if (!response.ok) {
      throw new DeviceError(`Provider HTTP ${response.status}`, "PROVIDER_REQUEST_FAILED", { status: response.status, payload });
    }
    const choice = (payload.choices as Array<Record<string, unknown>> | undefined)?.[0];
    const assistant = choice?.message as ChatMessage | undefined;
    if (!assistant) throw new DeviceError("Provider response has no assistant message", "PROVIDER_BAD_RESPONSE");
    const toolCalls: ProviderToolCall[] = ((assistant.tool_calls as Array<Record<string, unknown>> | undefined) ?? []).map((call) => {
      const fn = call.function as Record<string, unknown>;
      return { id: String(call.id), name: String(fn.name), arguments: String(fn.arguments ?? "{}") };
    });
    return {
      text: typeof assistant.content === "string" ? assistant.content : "",
      toolCalls,
      state: { messages, assistant },
    };
  }

  start(prompt: string, tools: GatewayTool[]): Promise<ProviderTurn<ChatState>> {
    return this.request([
      { role: "system", content: "Operate the mobile device using the supplied tools. Prefer invoke, then observe, then semantic act. Verify visible outcomes." },
      { role: "user", content: prompt },
    ], tools);
  }

  continue(state: ChatState, results: ToolResult[], tools: GatewayTool[]): Promise<ProviderTurn<ChatState>> {
    const messages = [
      ...state.messages,
      state.assistant,
      ...results.map((result) => ({ role: "tool", tool_call_id: result.callId, content: result.output })),
    ];
    return this.request(messages, tools);
  }
}
