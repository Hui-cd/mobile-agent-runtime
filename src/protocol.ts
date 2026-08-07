export type Platform = "android" | "ios";

export interface Bounds {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface UiNode {
  index: number;
  text?: string;
  content_description?: string;
  resource_id?: string;
  role: string;
  class_name: string;
  package?: string;
  clickable: boolean;
  enabled: boolean;
  focusable: boolean;
  focused: boolean;
  scrollable: boolean;
  selected: boolean;
  bounds: Bounds;
  center: { x: number; y: number };
}

export interface DeviceCapabilities {
  platform: Platform;
  adapter: string;
  connected: boolean;
  serial?: string;
  features: {
    screenshot: boolean;
    ui_tree: boolean;
    semantic_ui_control: boolean;
    notifications: boolean;
    unicode_input: boolean;
    invoke: string[];
  };
  limitations: string[];
}

export interface ObserveOptions {
  include_screen?: boolean;
  include_ui_tree?: boolean;
  include_notifications?: boolean;
  max_nodes?: number;
}

export interface Observation {
  observed_at: string;
  current_app?: string;
  current_activity?: string;
  keyboard_visible: boolean;
  ui_tree?: UiNode[];
  ui_tree_truncated?: boolean;
  notifications?: string[];
  screen?: {
    mime_type: "image/png";
    width?: number;
    height?: number;
    base64: string;
  };
  capabilities: DeviceCapabilities;
}

export interface ActTarget {
  text?: string;
  content_description?: string;
  resource_id?: string;
  role?: string;
  index?: number;
  x?: number;
  y?: number;
}

export interface ActRequest {
  action: "click" | "long_press" | "input" | "swipe" | "scroll" | "back" | "home";
  target?: ActTarget;
  value?: string;
  replace?: boolean;
  direction?: "up" | "down" | "left" | "right";
  distance?: number;
  duration_ms?: number;
}

export interface IntentExtra {
  key: string;
  type: "string" | "boolean" | "int" | "long" | "float";
  value: string | number | boolean;
}

export type InvokeRequest =
  | { capability: "open_app"; params: { package: string; activity?: string } }
  | { capability: "open_url" | "deep_link"; params: { url: string; package?: string } }
  | {
      capability: "intent";
      params: {
        action: string;
        data?: string;
        mime_type?: string;
        package?: string;
        component?: string;
        categories?: string[];
        extras?: IntentExtra[];
        flags?: number[];
      };
    }
  | { capability: "share"; params: { uri?: string; file?: string; mime_type?: string; text?: string; target_app?: string } }
  | { capability: "dial"; params: { number: string } }
  | { capability: "navigate"; params: { destination: string; mode?: "driving" | "walking" | "transit" } }
  | { capability: "open_file"; params: { uri?: string; file?: string; mime_type?: string } }
  | { capability: "open_settings"; params: { page?: string; package?: string } };

export interface ActionResult {
  ok: true;
  operation: string;
  elapsed_ms: number;
  observation: Observation;
  stability: {
    stable: boolean;
    waited_ms: number;
  };
}

export interface DeviceAdapter {
  observe(options?: ObserveOptions): Promise<Observation>;
  act(request: ActRequest): Promise<ActionResult>;
  invoke(request: InvokeRequest): Promise<ActionResult>;
}
