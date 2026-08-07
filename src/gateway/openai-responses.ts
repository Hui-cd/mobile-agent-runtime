import { DeviceError } from "../errors.js";
import type { AgentProvider, GatewayTool, ProviderToolCall, ProviderTurn, ToolResult } from "./types.js";

interface ResponsesState {
  responseId: string;
}

interface ResponsesOutputItem {
  type: string;
  call_id?: string;
  name?: string;
  arguments?: string;
  content?: Array<{ type: string; text?: string }>;
}

interface ResponsesPayload {
  id: string;
  output?: ResponsesOutputItem[];
  output_text?: string;
  error?: { message?: string } | null;
}

export interface OpenAIResponsesOptions {
  apiKey: string;
  model: string;
  baseUrl?: string;
  fetch?: typeof globalThis.fetch;
}

export class OpenAIResponsesProvider implements AgentProvider<ResponsesState> {
  private readonly baseUrl: string;
  private readonly fetcher: typeof globalThis.fetch;

  constructor(private readonly options: OpenAIResponsesOptions) {
    this.baseUrl = (options.baseUrl ?? "https://api.openai.com/v1").replace(/\/$/, "");
    this.fetcher = options.fetch ?? globalThis.fetch;
  }

  private providerTools(tools: GatewayTool[]): unknown[] {
    return tools.map((tool) => ({
      type: "function",
      name: tool.name,
      description: tool.description,
      parameters: tool.parameters,
    }));
  }

  private async request(body: Record<string, unknown>): Promise<ResponsesPayload> {
    const response = await this.fetcher(`${this.baseUrl}/responses`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${this.options.apiKey}`,
        "content-type": "application/json",
      },
      body: JSON.stringify(body),
    });
    const raw = await response.text();
    let payload: ResponsesPayload;
    try {
      payload = JSON.parse(raw) as ResponsesPayload;
    } catch {
      throw new DeviceError("Provider returned non-JSON data", "PROVIDER_BAD_RESPONSE", { status: response.status, body: raw.slice(0, 1_000) });
    }
    if (!response.ok || payload.error) {
      throw new DeviceError(payload.error?.message ?? `Provider HTTP ${response.status}`, "PROVIDER_REQUEST_FAILED", { status: response.status });
    }
    return payload;
  }

  private turn(payload: ResponsesPayload): ProviderTurn<ResponsesState> {
    const toolCalls: ProviderToolCall[] = [];
    const textParts: string[] = [];
    for (const item of payload.output ?? []) {
      if (item.type === "function_call" && item.call_id && item.name) {
        toolCalls.push({ id: item.call_id, name: item.name, arguments: item.arguments ?? "{}" });
      }
      for (const content of item.content ?? []) {
        if (content.type === "output_text" && content.text) textParts.push(content.text);
      }
    }
    return {
      text: payload.output_text ?? textParts.join("\n"),
      toolCalls,
      state: { responseId: payload.id },
    };
  }

  async start(prompt: string, tools: GatewayTool[]): Promise<ProviderTurn<ResponsesState>> {
    const payload = await this.request({
      model: this.options.model,
      instructions: "Operate the mobile device using the supplied tools. Prefer invoke, then observe, then semantic act. Verify visible outcomes.",
      input: prompt,
      tools: this.providerTools(tools),
      parallel_tool_calls: false,
    });
    return this.turn(payload);
  }

  async continue(state: ResponsesState, results: ToolResult[], tools: GatewayTool[]): Promise<ProviderTurn<ResponsesState>> {
    const payload = await this.request({
      model: this.options.model,
      previous_response_id: state.responseId,
      input: results.map((result) => ({ type: "function_call_output", call_id: result.callId, output: result.output })),
      tools: this.providerTools(tools),
      parallel_tool_calls: false,
    });
    return this.turn(payload);
  }
}
