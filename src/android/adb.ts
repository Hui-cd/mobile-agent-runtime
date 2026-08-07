import { spawn } from "node:child_process";
import { DeviceError } from "../errors.js";

export interface CommandResult {
  stdout: Buffer;
  stderr: string;
  exitCode: number;
}

export interface AdbExecutor {
  run(args: string[], options?: { timeoutMs?: number; maxBytes?: number }): Promise<CommandResult>;
}

export interface AdbClientOptions {
  binary?: string;
  serial?: string;
  timeoutMs?: number;
}

export function quoteRemoteShellArg(value: string): string {
  if (value.length === 0) return "''";
  return `'${value.replaceAll("'", `'\"'\"'`)}'`;
}

export function prepareAdbArgs(args: string[]): string[] {
  if (args[0] !== "shell" || args.length < 2) return args;
  return ["shell", args.slice(1).map(quoteRemoteShellArg).join(" ")];
}

export class AdbClient implements AdbExecutor {
  readonly binary: string;
  readonly serial?: string;
  readonly timeoutMs: number;

  constructor(options: AdbClientOptions = {}) {
    this.binary = options.binary ?? process.env.MOBILE_AGENT_ADB ?? "adb";
    this.serial = options.serial ?? process.env.ANDROID_SERIAL;
    this.timeoutMs = options.timeoutMs ?? 10_000;
  }

  async run(args: string[], options: { timeoutMs?: number; maxBytes?: number } = {}): Promise<CommandResult> {
    const preparedArgs = prepareAdbArgs(args);
    const adbArgs = this.serial ? ["-s", this.serial, ...preparedArgs] : preparedArgs;
    const timeoutMs = options.timeoutMs ?? this.timeoutMs;
    const maxBytes = options.maxBytes ?? 20 * 1024 * 1024;

    return await new Promise<CommandResult>((resolve, reject) => {
      const child = spawn(this.binary, adbArgs, { stdio: ["ignore", "pipe", "pipe"] });
      const stdout: Buffer[] = [];
      const stderr: Buffer[] = [];
      let byteCount = 0;
      let settled = false;

      const finishError = (error: Error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        reject(error);
      };

      child.stdout.on("data", (chunk: Buffer) => {
        byteCount += chunk.length;
        if (byteCount > maxBytes) {
          child.kill("SIGKILL");
          finishError(new DeviceError("ADB output exceeded the configured limit", "ADB_OUTPUT_TOO_LARGE", { maxBytes }));
          return;
        }
        stdout.push(chunk);
      });
      child.stderr.on("data", (chunk: Buffer) => stderr.push(chunk));
      child.on("error", (error) => {
        const message = (error as NodeJS.ErrnoException).code === "ENOENT"
          ? `ADB executable not found: ${this.binary}`
          : `Failed to start ADB: ${error.message}`;
        finishError(new DeviceError(message, "ADB_NOT_AVAILABLE", { binary: this.binary }));
      });
      child.on("close", (exitCode) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        const result = {
          stdout: Buffer.concat(stdout),
          stderr: Buffer.concat(stderr).toString("utf8").trim(),
          exitCode: exitCode ?? -1,
        };
        if (result.exitCode !== 0) {
          reject(new DeviceError(result.stderr || `ADB exited with code ${result.exitCode}`, "ADB_COMMAND_FAILED", {
            args,
            exitCode: result.exitCode,
          }));
          return;
        }
        resolve(result);
      });

      const timer = setTimeout(() => {
        child.kill("SIGKILL");
        finishError(new DeviceError(`ADB command timed out after ${timeoutMs} ms`, "ADB_TIMEOUT", { args, timeoutMs }));
      }, timeoutMs);
    });
  }
}

export function text(result: CommandResult): string {
  return result.stdout.toString("utf8").trim();
}
