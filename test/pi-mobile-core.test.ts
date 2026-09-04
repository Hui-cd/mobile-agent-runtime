import assert from "node:assert/strict";
import { test } from "node:test";
import { runPiMobileCoreFixture } from "../src/pi/mobile-core-fixture.js";

test("pi-agent-core executes the mobile native-tool fixture", async () => {
  const result = await runPiMobileCoreFixture(async () => ({ platform: "android" }));

  assert.equal(result.observedPlatform, "android");
  assert.equal(result.finalText, "Pi mobile fixture complete.");
  assert.deepEqual(result.messageRoles, ["user", "assistant", "toolResult", "assistant"]);
  assert.equal(result.eventTypes.filter((type) => type === "tool_execution_start").length, 1);
  assert.equal(result.eventTypes.filter((type) => type === "tool_execution_end").length, 1);
  assert.equal(result.eventTypes.at(-1), "agent_end");
});
