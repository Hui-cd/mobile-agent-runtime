import { runPiMobileCoreFixture } from "../dist/pi-mobile-core-fixture.js";

const result = await runPiMobileCoreFixture(async () => ({ platform: "browser-bundle" }));

if (result.observedPlatform !== "browser-bundle") {
  throw new Error(`Unexpected platform: ${result.observedPlatform}`);
}
if (result.finalText !== "Pi mobile fixture complete.") {
  throw new Error(`Unexpected final text: ${result.finalText}`);
}
if (result.eventTypes.at(-1) !== "agent_end") {
  throw new Error(`Unexpected final event: ${result.eventTypes.at(-1)}`);
}

console.log(JSON.stringify(result));
