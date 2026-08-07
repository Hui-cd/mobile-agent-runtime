export class DeviceError extends Error {
  constructor(
    message: string,
    readonly code: string,
    readonly details?: Record<string, unknown>,
  ) {
    super(message);
    this.name = "DeviceError";
  }
}

export function errorPayload(error: unknown): Record<string, unknown> {
  if (error instanceof DeviceError) {
    return {
      error: error.code,
      message: error.message,
      ...(error.details ? { details: error.details } : {}),
    };
  }
  return {
    error: "INTERNAL_ERROR",
    message: error instanceof Error ? error.message : String(error),
  };
}
