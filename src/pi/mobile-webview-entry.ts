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

type AndroidBridge = {
  postMessage(message: string): void;
  onmessage?: (event: { data: string }) => void;
};

declare global {
  interface Window {
    MobileAgentNative?: AndroidBridge;
    PiMobileRuntime?: {
      runFixture(): Promise<PiMobileCoreFixtureResult>;
      run(input: MobileAgentRunInput): Promise<MobileAgentRunResult>;
      cancel(): void;
    };
    webkit?: {
      messageHandlers?: {
        MobileAgentNative?: {
          postMessage(message: string): Promise<unknown>;
        };
      };
    };
  }
}

const pendingAndroidCalls = new Map<
  string,
  { resolve: (value: unknown) => void; reject: (error: Error) => void }
>();
let requestSequence = 0;
const runtime = new PiMobileAgentRuntime(callNative);

function installAndroidReplyHandler(bridge: AndroidBridge): void {
  if (bridge.onmessage) return;
  bridge.onmessage = (event) => {
    const response = parseJson(event.data) as NativeResponse;
    const pending = pendingAndroidCalls.get(response.id);
    if (!pending) return;
    pendingAndroidCalls.delete(response.id);
    if (response.error) pending.reject(new Error(response.error));
    else pending.resolve(response.result);
  };
}

async function callNative(method: NativeRequest["method"], params: Record<string, unknown>): Promise<unknown> {
  const request: NativeRequest = {
    id: `native-${++requestSequence}`,
    method,
    params,
  };
  const encoded = JSON.stringify(request);

  const iosBridge = window.webkit?.messageHandlers?.MobileAgentNative;
  if (iosBridge) {
    return parseNativeResult(await iosBridge.postMessage(encoded), request.id);
  }

  const androidBridge = window.MobileAgentNative;
  if (androidBridge) {
    installAndroidReplyHandler(androidBridge);
    return await new Promise((resolve, reject) => {
      pendingAndroidCalls.set(request.id, { resolve, reject });
      androidBridge.postMessage(encoded);
    });
  }

  throw new Error("MOBILE_NATIVE_BRIDGE_UNAVAILABLE");
}

async function runFixture(): Promise<PiMobileCoreFixtureResult> {
  let result: PiMobileCoreFixtureResult;
  try {
    result = await runPiMobileCoreFixture(async () => {
      const observation = await callNative("device_observe", {});
      if (!isPlatformObservation(observation)) {
        throw new Error("MOBILE_NATIVE_OBSERVATION_INVALID");
      }
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

function parseNativeResult(value: unknown, requestId: string): unknown {
  const response = typeof value === "string" ? (parseJson(value) as NativeResponse) : (value as NativeResponse);
  if (!response || response.id !== requestId) throw new Error("MOBILE_NATIVE_REPLY_MISMATCH");
  if (response.error) throw new Error(response.error);
  return response.result;
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

window.PiMobileRuntime = { runFixture, run, cancel: () => runtime.cancel() };
