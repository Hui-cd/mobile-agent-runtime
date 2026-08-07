#!/usr/bin/env node
import { serveStdio } from "@modelcontextprotocol/server/stdio";
import { AndroidAdapter } from "./android/android-adapter.js";
import { createMobileMcpServer } from "./mcp-server.js";

void serveStdio(() => createMobileMcpServer(new AndroidAdapter()), {
  onerror: (error) => console.error("mobile-agent-runtime:", error.message),
});

console.error("mobile-agent-runtime MCP server is listening on stdio");
