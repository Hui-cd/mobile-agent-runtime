import { runPiMobileCoreFixture, type PiMobileCoreFixtureResult } from "./mobile-core-fixture.js";
import { PiMobileAgentRuntime, type MobileAgentRunInput, type MobileAgentRunResult } from "./mobile-agent-runtime.js";

type NativeRequest = {
  id: string;
  method: string;
  params: Record<string, unknown>;
};

type NativeResponse = {
  id: string;
  result?: unknown;
  error?: string;
};

declare function mobileNativeCall(message: string): Promise<string>;

declare global {
  // QuickJS does not provide browser AbortController/queueMicrotask globals.
  // The fixture only needs the small standards-compatible surface below.
  var PiMobileQuickJsRuntime: {
    runFixture(): Promise<PiMobileCoreFixtureResult>;
    run(input: MobileAgentRunInput): Promise<MobileAgentRunResult>;
    cancel(): void;
  } | undefined;
}

let requestSequence = 0;
const runtime = new PiMobileAgentRuntime(callNative);

async function callNative(method: string, params: Record<string, unknown>): Promise<unknown> {
  const request: NativeRequest = {
    id: `native-${++requestSequence}`,
    method,
    params,
  };
  const response = parseJson(await mobileNativeCall(JSON.stringify(request))) as NativeResponse;
  if (!response || response.id !== request.id) throw new Error("MOBILE_NATIVE_REPLY_MISMATCH");
  if (response.error) throw new Error(response.error);
  return response.result;
}

async function runFixture(): Promise<PiMobileCoreFixtureResult> {
  let result: PiMobileCoreFixtureResult;
  try {
    result = await runPiMobileCoreFixture(async () => {
      const observation = await callNative("device_observe", {});
      if (!isPlatformObservation(observation)) throw new Error("MOBILE_NATIVE_OBSERVATION_INVALID");
      return observation;
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    await callNative("fixture_complete", { error: message }).catch(() => undefined);
    throw error;
  }
  await callNative("fixture_complete", { result });
  return result;
}

async function run(input: MobileAgentRunInput): Promise<MobileAgentRunResult> {
  try {
    const result = await runtime.run(input);
    await callNative("agent_complete", { result });
    return result;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    await callNative("agent_complete", { error: message }).catch(() => undefined);
    throw error;
  }
}

function parseJson(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    throw new Error("MOBILE_NATIVE_JSON_INVALID");
  }
}

function isPlatformObservation(value: unknown): value is { platform: string } {
  return typeof value === "object" && value !== null && typeof (value as { platform?: unknown }).platform === "string";
}

globalThis.PiMobileQuickJsRuntime = { runFixture, run, cancel: () => runtime.cancel() };
