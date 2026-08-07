import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { parseUiTree, selectNode } from "../src/android/ui-tree.js";

const fixture = new URL("./fixtures/window.xml", import.meta.url);

test("parseUiTree flattens accessible Android nodes", async () => {
  const parsed = parseUiTree(await readFile(fixture, "utf8"));
  assert.equal(parsed.truncated, false);
  assert.equal(parsed.nodes.length, 3);
  assert.deepEqual(parsed.nodes[1]?.center, { x: 300, y: 260 });
  assert.equal(parsed.nodes[1]?.role, "button");
  assert.equal(parsed.nodes[2]?.role, "text_field");
});

test("selectNode prefers a matching clickable node", async () => {
  const { nodes } = parseUiTree(await readFile(fixture, "utf8"));
  assert.equal(selectNode(nodes, { text: "Search" })?.resource_id, "com.example:id/search");
  assert.equal(selectNode(nodes, { role: "text_field" })?.content_description, "Message");
});

test("parseUiTree reports truncation", async () => {
  const parsed = parseUiTree(await readFile(fixture, "utf8"), 1);
  assert.equal(parsed.nodes.length, 1);
  assert.equal(parsed.truncated, true);
});
