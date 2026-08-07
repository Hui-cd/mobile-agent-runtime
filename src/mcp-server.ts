import { McpServer, type CallToolResult } from "@modelcontextprotocol/server";
import type { DeviceAdapter, Observation } from "./protocol.js";
import { errorPayload } from "./errors.js";
import { actSchema, invokeSchema, observeSchema } from "./schemas.js";

function withoutImageBase64(observation: Observation): Record<string, unknown> {
  if (!observation.screen) return observation as unknown as Record<string, unknown>;
  const { base64: _base64, ...screen } = observation.screen;
  return { ...observation, screen };
}

function successResult(value: unknown): CallToolResult {
  const record = value as Record<string, unknown>;
  const observation = (record.observation ?? value) as Observation;
  const publicValue = record.observation
    ? { ...record, observation: withoutImageBase64(observation) }
    : withoutImageBase64(observation);
  const content: CallToolResult["content"] = [
    { type: "text", text: JSON.stringify(publicValue, null, 2) },
  ];
  if (observation.screen) {
    content.push({ type: "image", mimeType: observation.screen.mime_type, data: observation.screen.base64 });
  }
  return { content, structuredContent: publicValue };
}

function failureResult(error: unknown): CallToolResult {
  const payload = errorPayload(error);
  return {
    isError: true,
    content: [{ type: "text", text: JSON.stringify(payload, null, 2) }],
    structuredContent: payload,
  };
}

export function createMobileMcpServer(adapter: DeviceAdapter): McpServer {
  const server = new McpServer(
    { name: "mobile-agent-runtime", version: "0.1.0" },
    {
      instructions: [
        "Use device.invoke first when a system capability can reach the destination directly.",
        "Use device.observe to inspect state, then device.act for semantic GUI interaction.",
        "device.act and device.invoke already wait for UI stability and return a fresh observation.",
        "Prefer semantic targets over coordinates. Verify user-visible side effects from the returned observation.",
      ].join(" "),
    },
  );

  server.registerTool(
    "device.observe",
    {
      title: "Observe mobile device",
      description: "Read the current mobile state: foreground app, screenshot, accessible UI nodes, keyboard, optional notifications, and capabilities.",
      inputSchema: observeSchema,
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    },
    async (input) => {
      try {
        return successResult(await adapter.observe(input));
      } catch (error) {
        return failureResult(error);
      }
    },
  );

  server.registerTool(
    "device.act",
    {
      title: "Act on mobile UI",
      description: "Perform one GUI action: click, long press, input, swipe, scroll, back, or home. Automatically waits and returns the resulting observation.",
      inputSchema: actSchema,
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false },
    },
    async (input) => {
      try {
        return successResult(await adapter.act(input));
      } catch (error) {
        return failureResult(error);
      }
    },
  );

  server.registerTool(
    "device.invoke",
    {
      title: "Invoke mobile system capability",
      description: "Invoke a structured system capability such as opening an app/URL, intent, share, dial, navigation, file, or settings. No arbitrary shell is exposed.",
      inputSchema: invokeSchema,
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true },
    },
    async (input) => {
      try {
        return successResult(await adapter.invoke(input));
      } catch (error) {
        return failureResult(error);
      }
    },
  );

  return server;
}
