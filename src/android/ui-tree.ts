import { XMLParser } from "fast-xml-parser";
import type { Bounds, UiNode } from "../protocol.js";

const parser = new XMLParser({
  ignoreAttributes: false,
  attributeNamePrefix: "",
  parseAttributeValue: false,
  allowBooleanAttributes: true,
});

function bool(value: unknown): boolean {
  return value === true || value === "true";
}

function bounds(value: unknown): Bounds | undefined {
  if (typeof value !== "string") return undefined;
  const match = value.match(/^\[(\d+),(\d+)]\[(\d+),(\d+)]$/);
  if (!match) return undefined;
  return {
    left: Number(match[1]),
    top: Number(match[2]),
    right: Number(match[3]),
    bottom: Number(match[4]),
  };
}

function roleFor(className: string): string {
  const name = className.toLowerCase();
  if (name.includes("edittext")) return "text_field";
  if (name.includes("button")) return "button";
  if (name.includes("checkbox")) return "checkbox";
  if (name.includes("radiobutton")) return "radio";
  if (name.includes("switch")) return "switch";
  if (name.includes("image")) return "image";
  if (name.includes("list") || name.includes("recyclerview")) return "list";
  if (name.includes("scroll")) return "scroll_view";
  if (name.includes("textview")) return "text";
  return "view";
}

function clean(value: unknown): string | undefined {
  return typeof value === "string" && value.length > 0 ? value : undefined;
}

export function parseUiTree(xml: string, maxNodes = 250): { nodes: UiNode[]; truncated: boolean } {
  const document = parser.parse(xml) as Record<string, unknown>;
  const nodes: UiNode[] = [];
  let encountered = 0;

  const visit = (value: unknown) => {
    if (!value || typeof value !== "object") return;
    const record = value as Record<string, unknown>;
    const children = record.node;

    if ("class" in record && "bounds" in record) {
      encountered += 1;
      if (nodes.length < maxNodes) {
        const nodeBounds = bounds(record.bounds);
        if (nodeBounds) {
          const className = String(record.class ?? "");
          nodes.push({
            index: nodes.length,
            text: clean(record.text),
            content_description: clean(record["content-desc"]),
            resource_id: clean(record["resource-id"]),
            role: roleFor(className),
            class_name: className,
            package: clean(record.package),
            clickable: bool(record.clickable),
            enabled: record.enabled === undefined ? true : bool(record.enabled),
            focusable: bool(record.focusable),
            focused: bool(record.focused),
            scrollable: bool(record.scrollable),
            selected: bool(record.selected),
            bounds: nodeBounds,
            center: {
              x: Math.round((nodeBounds.left + nodeBounds.right) / 2),
              y: Math.round((nodeBounds.top + nodeBounds.bottom) / 2),
            },
          });
        }
      }
    }

    if (Array.isArray(children)) children.forEach(visit);
    else visit(children);
  };

  const hierarchy = document.hierarchy as Record<string, unknown> | undefined;
  visit(hierarchy?.node);
  return { nodes, truncated: encountered > nodes.length };
}

export function selectNode(nodes: UiNode[], target: {
  text?: string;
  content_description?: string;
  resource_id?: string;
  role?: string;
  index?: number;
}): UiNode | undefined {
  if (target.index !== undefined) return nodes.find((node) => node.index === target.index);

  const matches = nodes.filter((node) => {
    if (target.text !== undefined && node.text !== target.text) return false;
    if (target.content_description !== undefined && node.content_description !== target.content_description) return false;
    if (target.resource_id !== undefined && node.resource_id !== target.resource_id) return false;
    if (target.role !== undefined && node.role !== target.role) return false;
    return true;
  });

  return matches.sort((a, b) => Number(b.clickable) - Number(a.clickable) || Number(b.enabled) - Number(a.enabled))[0];
}
