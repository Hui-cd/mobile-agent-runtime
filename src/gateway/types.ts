export interface GatewayTool {
  name: string;
  description: string;
  parameters: Record<string, unknown>;
}

export interface ProviderToolCall {
  id: string;
  name: string;
  arguments: string;
}

export interface ProviderTurn<State = unknown> {
  text: string;
  toolCalls: ProviderToolCall[];
  state: State;
}

export interface ToolResult {
  callId: string;
  name: string;
  output: string;
}

export interface AgentProvider<State = unknown> {
  start(prompt: string, tools: GatewayTool[]): Promise<ProviderTurn<State>>;
  continue(state: State, results: ToolResult[], tools: GatewayTool[]): Promise<ProviderTurn<State>>;
}

export interface GatewayTraceItem {
  step: number;
  tool: string;
  arguments: unknown;
  result: unknown;
}

export interface GatewayResult {
  output: string;
  steps: number;
  trace: GatewayTraceItem[];
}
