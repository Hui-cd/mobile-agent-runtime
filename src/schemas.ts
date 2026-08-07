import * as z from "zod/v4";

export const observeSchema = z.object({
  include_screen: z.boolean().default(true).describe("Return the current screenshot as MCP image content."),
  include_ui_tree: z.boolean().default(true).describe("Return accessibility-exposed UI nodes."),
  include_notifications: z.boolean().default(false).describe("Include a privacy-sensitive notification excerpt."),
  max_nodes: z.number().int().min(1).max(2_000).default(250),
});

export const targetSchema = z.object({
  text: z.string().optional().describe("Exact visible text."),
  content_description: z.string().optional().describe("Exact accessibility content description."),
  resource_id: z.string().optional().describe("Exact Android resource id."),
  role: z.enum(["text_field", "button", "checkbox", "radio", "switch", "image", "list", "scroll_view", "text", "view"]).optional(),
  index: z.number().int().nonnegative().optional().describe("Node index from the latest observation."),
  x: z.number().int().nonnegative().optional(),
  y: z.number().int().nonnegative().optional(),
}).refine((target) => {
  const coordinates = target.x !== undefined || target.y !== undefined;
  return !coordinates || (target.x !== undefined && target.y !== undefined);
}, "x and y must be supplied together");

export const actSchema = z.object({
  action: z.enum(["click", "long_press", "input", "swipe", "scroll", "back", "home"]),
  target: targetSchema.optional(),
  value: z.string().optional().describe("Text for the input action."),
  replace: z.boolean().default(false).describe("Select existing text before input when supported."),
  direction: z.enum(["up", "down", "left", "right"]).default("up"),
  distance: z.number().min(0.1).max(0.9).default(0.65),
  duration_ms: z.number().int().min(50).max(10_000).default(500),
}).superRefine((request, context) => {
  if (request.action === "input" && request.value === undefined) {
    context.addIssue({ code: "custom", path: ["value"], message: "input requires value" });
  }
  if (["click", "long_press"].includes(request.action) && request.target === undefined) {
    context.addIssue({ code: "custom", path: ["target"], message: `${request.action} requires target` });
  }
});

const extraSchema = z.object({
  key: z.string().min(1),
  type: z.enum(["string", "boolean", "int", "long", "float"]),
  value: z.union([z.string(), z.number(), z.boolean()]),
});

export const invokeSchema = z.discriminatedUnion("capability", [
  z.object({ capability: z.literal("open_app"), params: z.object({ package: z.string().min(1), activity: z.string().optional() }) }),
  z.object({ capability: z.literal("open_url"), params: z.object({ url: z.url(), package: z.string().optional() }) }),
  z.object({ capability: z.literal("deep_link"), params: z.object({ url: z.string().min(1), package: z.string().optional() }) }),
  z.object({
    capability: z.literal("intent"),
    params: z.object({
      action: z.string().min(1),
      data: z.string().optional(),
      mime_type: z.string().optional(),
      package: z.string().optional(),
      component: z.string().optional(),
      categories: z.array(z.string()).max(20).optional(),
      extras: z.array(extraSchema).max(50).optional(),
      flags: z.array(z.number().int().nonnegative()).max(20).optional(),
    }),
  }),
  z.object({
    capability: z.literal("share"),
    params: z.object({
      uri: z.string().optional(),
      file: z.string().optional(),
      mime_type: z.string().optional(),
      text: z.string().optional(),
      target_app: z.string().optional(),
    }).refine((params) => params.uri || params.file || params.text, "share requires uri, file, or text"),
  }),
  z.object({ capability: z.literal("dial"), params: z.object({ number: z.string().min(1) }) }),
  z.object({
    capability: z.literal("navigate"),
    params: z.object({ destination: z.string().min(1), mode: z.enum(["driving", "walking", "transit"]).default("driving") }),
  }),
  z.object({
    capability: z.literal("open_file"),
    params: z.object({ uri: z.string().optional(), file: z.string().optional(), mime_type: z.string().optional() })
      .refine((params) => params.uri || params.file, "open_file requires uri or file"),
  }),
  z.object({ capability: z.literal("open_settings"), params: z.object({ page: z.string().optional(), package: z.string().optional() }) }),
]);

export type ObserveInput = z.infer<typeof observeSchema>;
export type ActInput = z.infer<typeof actSchema>;
export type InvokeInput = z.infer<typeof invokeSchema>;
