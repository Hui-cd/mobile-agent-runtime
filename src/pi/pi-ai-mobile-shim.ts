import { Value } from "typebox/value";

type IteratorWaiter<T> = (result: IteratorResult<T>) => void;

/**
 * Mobile bundle surface consumed by pi-agent-core.
 *
 * Pi's default pi-ai entrypoint registers every model provider. Mobile builds
 * always inject a native-backed streamFn, so bundling those Node/provider SDKs
 * is unnecessary. Keep this shim intentionally small and covered by the Pi
 * lifecycle fixture.
 */
export class EventStream<TEvent, TResult> implements AsyncIterable<TEvent> {
  private readonly queue: TEvent[] = [];
  private readonly waiting: IteratorWaiter<TEvent>[] = [];
  private done = false;
  private readonly finalResultPromise: Promise<TResult>;
  private resolveFinalResult!: (result: TResult) => void;

  constructor(
    private readonly isComplete: (event: TEvent) => boolean,
    private readonly extractResult: (event: TEvent) => TResult,
  ) {
    this.finalResultPromise = new Promise((resolve) => {
      this.resolveFinalResult = resolve;
    });
  }

  push(event: TEvent): void {
    if (this.done) return;
    if (this.isComplete(event)) {
      this.done = true;
      this.resolveFinalResult(this.extractResult(event));
    }
    const waiter = this.waiting.shift();
    if (waiter) waiter({ value: event, done: false });
    else this.queue.push(event);
  }

  end(result?: TResult): void {
    this.done = true;
    if (result !== undefined) this.resolveFinalResult(result);
    while (this.waiting.length > 0) {
      this.waiting.shift()?.({ value: undefined, done: true });
    }
  }

  async *[Symbol.asyncIterator](): AsyncIterator<TEvent> {
    while (true) {
      const queued = this.queue.shift();
      if (queued !== undefined) {
        yield queued;
      } else if (this.done) {
        return;
      } else {
        const result = await new Promise<IteratorResult<TEvent>>((resolve) => this.waiting.push(resolve));
        if (result.done) return;
        yield result.value;
      }
    }
  }

  result(): Promise<TResult> {
    return this.finalResultPromise;
  }
}

type AssistantMessage = {
  stopReason: string;
};

type AssistantEvent = {
  type: string;
  message?: AssistantMessage;
  error?: AssistantMessage;
  [key: string]: unknown;
};

export class AssistantMessageEventStream extends EventStream<AssistantEvent, AssistantMessage> {
  constructor() {
    super(
      (event) => event.type === "done" || event.type === "error",
      (event) => {
        if (event.type === "done" && event.message) return event.message;
        if (event.type === "error" && event.error) return event.error;
        throw new Error("Unexpected event type for final result");
      },
    );
  }
}

export function createAssistantMessageEventStream(): AssistantMessageEventStream {
  return new AssistantMessageEventStream();
}

export function validateToolArguments(
  tool: { name: string; parameters: Parameters<typeof Value.Check>[0] },
  toolCall: { name: string; arguments: unknown },
): unknown {
  const args = clone(toolCall.arguments);
  Value.Convert(tool.parameters, args);
  if (Value.Check(tool.parameters, args)) return args;

  const errors = [...Value.Errors(tool.parameters, args)]
    .map((error) => {
      const path = (error as { instancePath?: string }).instancePath;
      return `  - ${path || "root"}: ${error.message}`;
    })
    .join("\n");
  throw new Error(
    `Validation failed for tool "${tool.name}":\n${errors || "Unknown validation error"}\n\n` +
      `Received arguments:\n${JSON.stringify(toolCall.arguments, null, 2)}`,
  );
}

/** Pi core never calls this when the mobile runtime supplies streamFn. */
export function streamSimple(): never {
  throw new Error("MOBILE_STREAM_FN_REQUIRED");
}

/** Satisfies Pi's tree-shaken proxy export; the mobile runtime does not expose streamProxy. */
export function parseStreamingJson(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return {};
  }
}

function clone<T>(value: T): T {
  if (typeof structuredClone === "function") return structuredClone(value);
  return JSON.parse(JSON.stringify(value)) as T;
}
