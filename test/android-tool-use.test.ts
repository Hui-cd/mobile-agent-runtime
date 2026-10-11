import { test } from "node:test";
import assert from "node:assert/strict";
const path = "../scripts/android-tool-use.mjs";
const { evaluateToolTask } = await import(path);

function pair(id: string, name: string, args: Record<string, unknown>, observation: Record<string, unknown>) {
  return [
    { role: "assistant", content: [{ type: "toolCall", id, name, arguments: args }] },
    { role: "toolResult", toolCallId: id, isError: false, content: [{ type: "text", text: JSON.stringify(observation) }] },
  ];
}

test("clock acceptance requires observing the intermediate tab, not merely two clicks", () => {
  const app = "com.google.android.deskclock";
  const alarm = { current_app: app, ui_tree: [{ visible: true, selected: true, content_description: "Alarm" }] };
  const watch = { current_app: app, ui_tree: [{ visible: true, selected: true, content_description: "Stopwatch" }] };
  const record = { environment: "emulator", dev_only: true, status: "completed_unverified", result: { selected_tab: "Alarm" },
    completion_evidence: { claim_follows_tool_evidence: true, last_observation: { current_app: app } } };
  const trace = (middle: unknown) => [
    ...pair("discover", "device_observe", { include_apps: true }, {}),
    ...pair("click-1", "device_act", { action: "click" }, middle as Record<string, unknown>),
    ...pair("click-2", "device_act", { action: "click" }, alarm),
  ];
  assert.equal(evaluateToolTask("clock", record, trace(watch)).ok, true);
  assert.equal(evaluateToolTask("clock", record, trace(alarm)).ok, false);
});

test("settings acceptance rejects invisible results and searches without a fresh input", () => {
  const app = "com.google.android.settings.intelligence";
  const observation = { current_app: app, ui_tree: [
    { visible: true, editable: true, text: "bluetooth" },
    ...["Bluetooth", "Bluetooth tethering", "USB tethering"].map((text) => ({ visible: true, text })),
    { visible: false, text: "Hidden result" },
  ] };
  const record = { environment: "emulator", dev_only: true, status: "completed_unverified",
    result: { query: "bluetooth", items: ["Bluetooth", "Bluetooth tethering", "USB tethering"] },
    completion_evidence: { claim_follows_tool_evidence: true, last_observation: { current_app: app } } };
  const trace = pair("input", "device_act", { action: "input", value: "bluetooth" }, observation);
  assert.equal(evaluateToolTask("settings", record, trace).ok, true);
  assert.equal(evaluateToolTask("settings", { ...record, result: { query: "bluetooth", items: ["Bluetooth", "Bluetooth tethering", "Hidden result"] } }, trace).ok, false);
  assert.equal(evaluateToolTask("settings", record, pair("observe", "device_observe", {}, observation)).ok, false);
});
