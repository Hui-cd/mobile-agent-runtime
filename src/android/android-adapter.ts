import { createHash } from "node:crypto";
import { accessSync, constants } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { DeviceError } from "../errors.js";
import type {
  ActRequest,
  ActTarget,
  ActionResult,
  DeviceCapabilities,
  DeviceAdapter,
  InvokeRequest,
  ObserveOptions,
  Observation,
  UiNode,
} from "../protocol.js";
import { AdbClient, type AdbExecutor, text } from "./adb.js";
import { parseUiTree, selectNode } from "./ui-tree.js";

const UI_DUMP_PATH = "/sdcard/mobile_agent_window.xml";

function existingAdb(): string {
  if (process.env.MOBILE_AGENT_ADB) return process.env.MOBILE_AGENT_ADB;
  const candidates = [
    process.env.ANDROID_HOME && join(process.env.ANDROID_HOME, "platform-tools", "adb"),
    process.env.ANDROID_SDK_ROOT && join(process.env.ANDROID_SDK_ROOT, "platform-tools", "adb"),
    join(homedir(), "Library", "Android", "sdk", "platform-tools", "adb"),
  ].filter((value): value is string => Boolean(value));
  for (const candidate of candidates) {
    try {
      accessSync(candidate, constants.X_OK);
      return candidate;
    } catch {
      // Continue to the next standard SDK location.
    }
  }
  return "adb";
}

function pngSize(buffer: Buffer): { width?: number; height?: number } {
  if (buffer.length >= 24 && buffer.subarray(1, 4).toString("ascii") === "PNG") {
    return { width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) };
  }
  return {};
}

function currentWindow(raw: string): { app?: string; activity?: string } {
  const match = raw.match(/(?:topResumedActivity|mResumedActivity|mCurrentFocus|mFocusedApp).*?\s([A-Za-z0-9._]+)\/([A-Za-z0-9._$]+)/);
  return match ? { app: match[1], activity: match[2] } : {};
}

function keyboardShown(raw: string): boolean {
  return /(?:mInputShown|inputShown|mIsInputViewShown)=true/.test(raw);
}

function deviceFileUri(path: string): string {
  if (path.startsWith("content://") || path.startsWith("file://")) return path;
  const prefix = "/storage/emulated/0/";
  if (!path.startsWith(prefix)) {
    throw new DeviceError(
      "Only public shared-storage paths under /storage/emulated/0 are supported; pass a content:// URI for other providers",
      "UNSUPPORTED_FILE_URI",
      { path },
    );
  }
  const documentId = `primary:${path.slice(prefix.length)}`;
  return `content://com.android.externalstorage.documents/document/${encodeURIComponent(documentId)}`;
}

function encodeAdbText(value: string): string {
  return value.replaceAll(" ", "%s");
}

function isAscii(value: string): boolean {
  return /^[\x00-\x7F]*$/.test(value);
}

function notificationExcerpt(raw: string): string[] {
  return raw
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => /(?:pkg=|android\.title=|android\.text=|tickerText=)/.test(line))
    .slice(0, 100);
}

export interface AndroidAdapterOptions {
  adb?: AdbExecutor;
  serial?: string;
  settleTimeoutMs?: number;
  settlePollMs?: number;
}

export class AndroidAdapter implements DeviceAdapter {
  private readonly adb: AdbExecutor;
  private readonly serial?: string;
  private readonly settleTimeoutMs: number;
  private readonly settlePollMs: number;

  constructor(options: AndroidAdapterOptions = {}) {
    this.adb = options.adb ?? new AdbClient({ binary: existingAdb(), serial: options.serial });
    this.serial = options.serial ?? process.env.ANDROID_SERIAL;
    this.settleTimeoutMs = options.settleTimeoutMs ?? 3_500;
    this.settlePollMs = options.settlePollMs ?? 300;
  }

  private async connected(): Promise<boolean> {
    try {
      return text(await this.adb.run(["get-state"], { timeoutMs: 3_000 })) === "device";
    } catch {
      return false;
    }
  }

  private capabilities(connected: boolean): DeviceCapabilities {
    const unicodeInput = Boolean(process.env.MOBILE_AGENT_UNICODE_IME_ACTION);
    return {
      platform: "android",
      adapter: "adb-uiautomator",
      connected,
      serial: this.serial,
      features: {
        screenshot: connected,
        ui_tree: connected,
        semantic_ui_control: connected,
        notifications: connected,
        unicode_input: connected && unicodeInput,
        invoke: ["open_app", "open_url", "deep_link", "intent", "share", "dial", "navigate", "open_file", "open_settings"],
      },
      limitations: [
        "UIAutomator can only see accessibility-exposed nodes.",
        "Private app data remains inaccessible without app-granted access.",
        unicodeInput
          ? "Unicode input depends on the configured companion IME broadcast action."
          : "ADB input supports ASCII only; configure a companion Unicode IME for CJK and other Unicode text.",
      ],
    };
  }

  private async ensureConnected(): Promise<void> {
    if (!(await this.connected())) {
      throw new DeviceError(
        "No authorized Android device is connected. Start an emulator or enable USB debugging and accept the authorization prompt.",
        "DEVICE_NOT_CONNECTED",
      );
    }
  }

  private async uiXml(): Promise<string> {
    await this.adb.run(["shell", "uiautomator", "dump", UI_DUMP_PATH], { timeoutMs: 8_000 });
    return text(await this.adb.run(["exec-out", "cat", UI_DUMP_PATH], { timeoutMs: 5_000, maxBytes: 8 * 1024 * 1024 }));
  }

  private async uiNodes(maxNodes = 5_000): Promise<UiNode[]> {
    return parseUiTree(await this.uiXml(), maxNodes).nodes;
  }

  async observe(options: ObserveOptions = {}): Promise<Observation> {
    const connected = await this.connected();
    const result: Observation = {
      observed_at: new Date().toISOString(),
      keyboard_visible: false,
      capabilities: this.capabilities(connected),
    };
    if (!connected) return result;

    const includeScreen = options.include_screen ?? true;
    const includeUiTree = options.include_ui_tree ?? true;
    const maxNodes = options.max_nodes ?? 250;

    const [windowState, inputState, screen, ui, notifications] = await Promise.allSettled([
      this.adb.run(["shell", "dumpsys", "activity", "activities"], { maxBytes: 6 * 1024 * 1024 }),
      this.adb.run(["shell", "dumpsys", "input_method"], { maxBytes: 4 * 1024 * 1024 }),
      includeScreen ? this.adb.run(["exec-out", "screencap", "-p"], { maxBytes: 25 * 1024 * 1024 }) : Promise.resolve(undefined),
      includeUiTree ? this.uiXml() : Promise.resolve(undefined),
      options.include_notifications
        ? this.adb.run(["shell", "dumpsys", "notification", "--noredact"], { maxBytes: 8 * 1024 * 1024 })
        : Promise.resolve(undefined),
    ]);

    if (windowState.status === "fulfilled") {
      const foreground = currentWindow(text(windowState.value));
      result.current_app = foreground.app;
      result.current_activity = foreground.activity;
    }
    if (inputState.status === "fulfilled") result.keyboard_visible = keyboardShown(text(inputState.value));
    if (screen.status === "fulfilled" && screen.value) {
      result.screen = {
        mime_type: "image/png",
        ...pngSize(screen.value.stdout),
        base64: screen.value.stdout.toString("base64"),
      };
    }
    if (ui.status === "fulfilled" && ui.value) {
      const parsed = parseUiTree(ui.value, maxNodes);
      result.ui_tree = parsed.nodes;
      result.ui_tree_truncated = parsed.truncated;
    }
    if (notifications.status === "fulfilled" && notifications.value) {
      result.notifications = notificationExcerpt(text(notifications.value));
    }

    return result;
  }

  private async targetNode(target?: ActTarget): Promise<UiNode> {
    if (!target) throw new DeviceError("This action requires a target", "TARGET_REQUIRED");
    if (target.x !== undefined && target.y !== undefined) {
      return {
        index: -1,
        role: "coordinate",
        class_name: "coordinate",
        clickable: true,
        enabled: true,
        focusable: false,
        focused: false,
        scrollable: false,
        selected: false,
        bounds: { left: target.x, top: target.y, right: target.x, bottom: target.y },
        center: { x: target.x, y: target.y },
      };
    }
    const node = selectNode(await this.uiNodes(), target);
    if (!node) throw new DeviceError("No UI node matched the requested target", "TARGET_NOT_FOUND", { target });
    if (!node.enabled) throw new DeviceError("The matched UI node is disabled", "TARGET_DISABLED", { target, node });
    return node;
  }

  private async screenSize(): Promise<{ width: number; height: number }> {
    const raw = text(await this.adb.run(["shell", "wm", "size"]));
    const matches = [...raw.matchAll(/(\d+)x(\d+)/g)];
    const match = matches.at(-1);
    if (!match) throw new DeviceError("Could not determine the device screen size", "SCREEN_SIZE_UNKNOWN", { raw });
    return { width: Number(match[1]), height: Number(match[2]) };
  }

  private async input(value: string, replace = false): Promise<void> {
    if (replace) {
      await this.adb.run(["shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A"]);
      await this.adb.run(["shell", "input", "keyevent", "KEYCODE_DEL"]);
    }
    if (isAscii(value)) {
      await this.adb.run(["shell", "input", "text", encodeAdbText(value)]);
      return;
    }
    const action = process.env.MOBILE_AGENT_UNICODE_IME_ACTION;
    if (!action) {
      throw new DeviceError(
        "Unicode input needs a companion IME. Set MOBILE_AGENT_UNICODE_IME_ACTION to its base64 broadcast action.",
        "UNICODE_INPUT_UNAVAILABLE",
      );
    }
    await this.adb.run([
      "shell", "am", "broadcast", "-a", action,
      "--es", "msg", Buffer.from(value, "utf8").toString("base64"),
    ]);
  }

  private async performAct(request: ActRequest): Promise<void> {
    const duration = Math.max(50, Math.min(request.duration_ms ?? 500, 10_000));
    if (request.action === "back") {
      await this.adb.run(["shell", "input", "keyevent", "KEYCODE_BACK"]);
      return;
    }
    if (request.action === "home") {
      await this.adb.run(["shell", "input", "keyevent", "KEYCODE_HOME"]);
      return;
    }

    if (request.action === "click" || request.action === "long_press" || request.action === "input") {
      const node = await this.targetNode(request.target ?? (request.action === "input" ? { role: "text_field" } : undefined));
      if (request.action === "click" || request.action === "input") {
        await this.adb.run(["shell", "input", "tap", String(node.center.x), String(node.center.y)]);
      } else {
        await this.adb.run([
          "shell", "input", "swipe",
          String(node.center.x), String(node.center.y), String(node.center.x), String(node.center.y), String(duration),
        ]);
      }
      if (request.action === "input") {
        if (request.value === undefined) throw new DeviceError("input requires value", "VALUE_REQUIRED");
        await this.input(request.value, request.replace);
      }
      return;
    }

    const { width, height } = await this.screenSize();
    const target = request.target ? await this.targetNode(request.target) : undefined;
    const area = target?.bounds ?? { left: 0, top: 0, right: width, bottom: height };
    const distance = Math.max(0.1, Math.min(request.distance ?? 0.65, 0.9));
    const direction = request.direction ?? "up";
    const centerX = Math.round((area.left + area.right) / 2);
    const centerY = Math.round((area.top + area.bottom) / 2);
    const deltaX = Math.round((area.right - area.left) * distance / 2);
    const deltaY = Math.round((area.bottom - area.top) * distance / 2);
    let start = { x: centerX, y: centerY };
    let end = { x: centerX, y: centerY };
    if (direction === "up") [start, end] = [{ x: centerX, y: centerY + deltaY }, { x: centerX, y: centerY - deltaY }];
    if (direction === "down") [start, end] = [{ x: centerX, y: centerY - deltaY }, { x: centerX, y: centerY + deltaY }];
    if (direction === "left") [start, end] = [{ x: centerX + deltaX, y: centerY }, { x: centerX - deltaX, y: centerY }];
    if (direction === "right") [start, end] = [{ x: centerX - deltaX, y: centerY }, { x: centerX + deltaX, y: centerY }];
    await this.adb.run([
      "shell", "input", "swipe",
      String(start.x), String(start.y), String(end.x), String(end.y), String(duration),
    ]);
  }

  private intentArgs(request: InvokeRequest): string[] {
    const start = ["shell", "am", "start", "-W"];
    switch (request.capability) {
      case "open_app":
        if (request.params.activity) return [...start, "-n", `${request.params.package}/${request.params.activity}`];
        return ["shell", "monkey", "-p", request.params.package, "-c", "android.intent.category.LAUNCHER", "1"];
      case "open_url":
      case "deep_link":
        return [...start, "-a", "android.intent.action.VIEW", "-d", request.params.url,
          ...(request.params.package ? ["-p", request.params.package] : [])];
      case "dial":
        return [...start, "-a", "android.intent.action.DIAL", "-d", `tel:${request.params.number}`];
      case "navigate": {
        const mode = request.params.mode === "walking" ? "w" : request.params.mode === "transit" ? "r" : "d";
        const uri = `google.navigation:q=${encodeURIComponent(request.params.destination)}&mode=${mode}`;
        return [...start, "-a", "android.intent.action.VIEW", "-d", uri];
      }
      case "open_settings": {
        const pages: Record<string, string> = {
          main: "android.settings.SETTINGS",
          accessibility: "android.settings.ACCESSIBILITY_SETTINGS",
          wifi: "android.settings.WIFI_SETTINGS",
          bluetooth: "android.settings.BLUETOOTH_SETTINGS",
          notifications: "android.settings.NOTIFICATION_SETTINGS",
          app_details: "android.settings.APPLICATION_DETAILS_SETTINGS",
        };
        const action = pages[request.params.page ?? "main"] ?? request.params.page ?? pages.main!;
        return [...start, "-a", action,
          ...(request.params.package ? ["-d", `package:${request.params.package}`] : [])];
      }
      case "open_file": {
        const uri = request.params.uri ?? (request.params.file ? deviceFileUri(request.params.file) : undefined);
        if (!uri) throw new DeviceError("open_file requires uri or file", "PARAMS_REQUIRED");
        return [...start, "-a", "android.intent.action.VIEW", "-d", uri, "-t", request.params.mime_type ?? "*/*", "--grant-read-uri-permission"];
      }
      case "share": {
        const args = [...start, "-a", "android.intent.action.SEND", "-t", request.params.mime_type ?? "*/*"];
        if (request.params.text) args.push("--es", "android.intent.extra.TEXT", request.params.text);
        const uri = request.params.uri ?? (request.params.file ? deviceFileUri(request.params.file) : undefined);
        if (uri) args.push("--eu", "android.intent.extra.STREAM", uri, "--grant-read-uri-permission");
        if (request.params.target_app) args.push("-p", request.params.target_app);
        return args;
      }
      case "intent": {
        const args = [...start, "-a", request.params.action];
        if (request.params.data) args.push("-d", request.params.data);
        if (request.params.mime_type) args.push("-t", request.params.mime_type);
        if (request.params.package) args.push("-p", request.params.package);
        if (request.params.component) args.push("-n", request.params.component);
        for (const category of request.params.categories ?? []) args.push("-c", category);
        for (const flag of request.params.flags ?? []) args.push("-f", String(flag));
        const switches = { string: "--es", boolean: "--ez", int: "--ei", long: "--el", float: "--ef" } as const;
        for (const extra of request.params.extras ?? []) args.push(switches[extra.type], extra.key, String(extra.value));
        return args;
      }
    }
  }

  private async waitForStable(): Promise<{ stable: boolean; waited_ms: number }> {
    const started = Date.now();
    let previous: string | undefined;
    let repeats = 0;
    await new Promise((resolve) => setTimeout(resolve, this.settlePollMs));

    while (Date.now() - started < this.settleTimeoutMs) {
      try {
        const signature = createHash("sha1").update(await this.uiXml()).digest("hex");
        repeats = signature === previous ? repeats + 1 : 0;
        previous = signature;
        if (repeats >= 1) return { stable: true, waited_ms: Date.now() - started };
      } catch {
        // Transient windows may not expose a hierarchy while changing.
      }
      await new Promise((resolve) => setTimeout(resolve, this.settlePollMs));
    }
    return { stable: false, waited_ms: Date.now() - started };
  }

  async act(request: ActRequest): Promise<ActionResult> {
    const started = Date.now();
    await this.ensureConnected();
    await this.performAct(request);
    const stability = await this.waitForStable();
    const observation = await this.observe();
    return { ok: true, operation: request.action, elapsed_ms: Date.now() - started, observation, stability };
  }

  async invoke(request: InvokeRequest): Promise<ActionResult> {
    const started = Date.now();
    await this.ensureConnected();
    await this.adb.run(this.intentArgs(request));
    const stability = await this.waitForStable();
    const observation = await this.observe();
    return { ok: true, operation: request.capability, elapsed_ms: Date.now() - started, observation, stability };
  }
}
