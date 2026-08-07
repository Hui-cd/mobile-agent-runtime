#!/usr/bin/env node
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { AndroidAdapter } from "../android/android-adapter.js";
import { errorPayload } from "../errors.js";
import { AgentGateway } from "./agent-gateway.js";
import { MobileToolRegistry } from "./mobile-tools.js";
import { OpenAICompatibleChatProvider } from "./openai-compatible-chat.js";
import { OpenAIResponsesProvider } from "./openai-responses.js";
import type { AgentProvider } from "./types.js";

function env(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

function providerFromEnvironment(): AgentProvider<unknown> {
  const kind = process.env.MOBILE_AGENT_PROVIDER ?? "openai-responses";
  const apiKey = env("MOBILE_AGENT_API_KEY");
  const model = env("MOBILE_AGENT_MODEL");
  if (kind === "openai-responses") {
    return new OpenAIResponsesProvider({
      apiKey,
      model,
      baseUrl: process.env.MOBILE_AGENT_BASE_URL ?? "https://api.openai.com/v1",
    }) as AgentProvider<unknown>;
  }
  if (kind === "openai-compatible-chat") {
    return new OpenAICompatibleChatProvider({ apiKey, model, baseUrl: env("MOBILE_AGENT_BASE_URL") }) as AgentProvider<unknown>;
  }
  throw new Error(`Unsupported MOBILE_AGENT_PROVIDER: ${kind}`);
}

async function readJson(request: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let bytes = 0;
  for await (const chunk of request) {
    const buffer = Buffer.from(chunk);
    bytes += buffer.length;
    if (bytes > 1024 * 1024) throw new Error("Request body exceeds 1 MiB");
    chunks.push(buffer);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<string, unknown>;
}

function json(response: ServerResponse, status: number, payload: unknown): void {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  response.end(JSON.stringify(payload));
}

const host = process.env.MOBILE_AGENT_GATEWAY_HOST ?? "127.0.0.1";
const port = Number(process.env.MOBILE_AGENT_GATEWAY_PORT ?? "8787");
const token = process.env.MOBILE_AGENT_GATEWAY_TOKEN;
if (!["127.0.0.1", "localhost", "::1"].includes(host) && !token) {
  throw new Error("MOBILE_AGENT_GATEWAY_TOKEN is required when binding outside loopback");
}

const gateway = new AgentGateway(providerFromEnvironment(), new MobileToolRegistry(new AndroidAdapter()));
const server = createServer(async (request, response) => {
  try {
    if (request.method === "GET" && request.url === "/health") {
      json(response, 200, { ok: true });
      return;
    }
    if (request.method !== "POST" || request.url !== "/tasks") {
      json(response, 404, { error: "NOT_FOUND" });
      return;
    }
    if (token && request.headers.authorization !== `Bearer ${token}`) {
      json(response, 401, { error: "UNAUTHORIZED" });
      return;
    }
    const body = await readJson(request);
    if (typeof body.prompt !== "string" || body.prompt.trim().length === 0) {
      json(response, 400, { error: "INVALID_REQUEST", message: "prompt must be a non-empty string" });
      return;
    }
    const result = await gateway.run(body.prompt, {
      allowDeviceActions: body.allow_device_actions === true,
      maxSteps: typeof body.max_steps === "number" ? body.max_steps : undefined,
    });
    json(response, 200, result);
  } catch (error) {
    json(response, 500, errorPayload(error));
  }
});

server.listen(port, host, () => {
  console.error(`mobile-agent gateway listening on http://${host}:${port}`);
});
