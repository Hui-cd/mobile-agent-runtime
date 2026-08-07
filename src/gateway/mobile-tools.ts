import * as z from "zod/v4";
import { errorPayload } from "../errors.js";
import type { DeviceAdapter, Observation } from "../protocol.js";
import { actSchema, invokeSchema, observeSchema } from "../schemas.js";
import type { GatewayTool } from "./types.js";

const definitions = [
  {
    externalName: "device_observe",
    description: "Read foreground app, accessibility UI nodes, keyboard state, and device capabilities.",
    schema: observeSchema,
    mutating: false,
  },
  {
    externalName: "device_act",
    description: "Perform one semantic GUI action, wait for stability, and return the resulting observation.",
    schema: actSchema,
    mutating: true,
  },
  {
    externalName: "device_invoke",
    description: "Invoke one structured mobile system capability, wait for stability, and return the resulting observation.",
    schema: invokeSchema,
    mutating: true,
  },
] as const;

function stripScreen(value: unknown): unknown {
  if (!value || typeof value !== "object") return value;
  const record = value as Record<string, unknown>;
  const observation = (record.observation ?? value) as Observation;
  if (!observation.screen) return value;
  const { base64: _base64, ...screen } = observation.screen;
  const publicObservation = { ...observation, screen };
  return record.observation ? { ...record, observation: publicObservation } : publicObservation;
}

export class MobileToolRegistry {
  constructor(private readonly adapter: DeviceAdapter) {}

  list(): GatewayTool[] {
    return definitions.map((definition) => {
      const { $schema: _schema, ...parameters } = z.toJSONSchema(definition.schema, { io: "input", target: "draft-7" }) as Record<string, unknown>;
      return { name: definition.externalName, description: definition.description, parameters };
    });
  }

  async execute(name: string, rawArguments: string, allowDeviceActions: boolean): Promise<{ parsed: unknown; output: string; value: unknown }> {
    const definition = definitions.find((candidate) => candidate.externalName === name);
    if (!definition) {
      const value = { error: "UNKNOWN_TOOL", message: `Unknown tool: ${name}` };
      return { parsed: {}, value, output: JSON.stringify(value) };
    }

    let parsedJson: unknown;
    try {
      parsedJson = JSON.parse(rawArguments || "{}");
    } catch {
      const value = { error: "INVALID_TOOL_ARGUMENTS", message: "Tool arguments are not valid JSON" };
      return { parsed: rawArguments, value, output: JSON.stringify(value) };
    }

    if (definition.mutating && !allowDeviceActions) {
      const value = {
        error: "DEVICE_ACTIONS_NOT_AUTHORIZED",
        message: "This task did not set allow_device_actions=true; only device_observe is permitted.",
      };
      return { parsed: parsedJson, value, output: JSON.stringify(value) };
    }

    try {
      if (name === "device_observe") {
        const input = observeSchema.parse(parsedJson);
        const value = stripScreen(await this.adapter.observe({ ...input, include_screen: false }));
        return { parsed: input, value, output: JSON.stringify(value) };
      }
      if (name === "device_act") {
        const input = actSchema.parse(parsedJson);
        const value = stripScreen(await this.adapter.act(input));
        return { parsed: input, value, output: JSON.stringify(value) };
      }
      const input = invokeSchema.parse(parsedJson);
      const value = stripScreen(await this.adapter.invoke(input));
      return { parsed: input, value, output: JSON.stringify(value) };
    } catch (error) {
      const value = error instanceof z.ZodError
        ? { error: "INVALID_TOOL_ARGUMENTS", message: z.prettifyError(error) }
        : errorPayload(error);
      return { parsed: parsedJson, value, output: JSON.stringify(value) };
    }
  }
}
