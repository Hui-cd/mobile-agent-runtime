import { DeviceError } from "../errors.js";
import { MobileToolRegistry } from "./mobile-tools.js";
import type { AgentProvider, GatewayResult, GatewayTraceItem, ToolResult } from "./types.js";

export interface AgentGatewayRunOptions {
  allowDeviceActions?: boolean;
  maxSteps?: number;
}

export class AgentGateway<State = unknown> {
  constructor(
    private readonly provider: AgentProvider<State>,
    private readonly tools: MobileToolRegistry,
  ) {}

  async run(prompt: string, options: AgentGatewayRunOptions = {}): Promise<GatewayResult> {
    const maxSteps = Math.max(1, Math.min(options.maxSteps ?? 20, 50));
    const definitions = this.tools.list();
    const trace: GatewayTraceItem[] = [];
    let turn = await this.provider.start(prompt, definitions);

    for (let step = 1; step <= maxSteps; step += 1) {
      if (turn.toolCalls.length === 0) {
        return { output: turn.text, steps: step - 1, trace };
      }

      const results: ToolResult[] = [];
      // Device actions are intentionally serialized even if a provider emits parallel calls.
      for (const call of turn.toolCalls) {
        const execution = await this.tools.execute(call.name, call.arguments, options.allowDeviceActions ?? false);
        trace.push({ step, tool: call.name, arguments: execution.parsed, result: execution.value });
        results.push({ callId: call.id, name: call.name, output: execution.output });
      }
      turn = await this.provider.continue(turn.state, results, definitions);
    }

    throw new DeviceError(`Agent exceeded the ${maxSteps}-step limit`, "AGENT_STEP_LIMIT", { maxSteps, trace });
  }
}
