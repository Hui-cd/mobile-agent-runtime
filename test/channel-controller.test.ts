import { test } from "node:test";
import assert from "node:assert/strict";
const path = "../scripts/channel-controller.mjs";
const { ChannelController } = await import(path);

function setup(run = async () => "observed clock") {
  const replies: string[] = [];
  const seen = new Set<string>();
  let calls = 0;
  let stops = 0;
  const controller = new ChannelController({
    userId: "owner", now: () => 1000000,
    claim(id: string) { if (seen.has(id)) return false; seen.add(id); return true; },
    run: async () => { calls++; return run(); },
    stop: async () => { stops++; },
    reply: async (_id: string, text: string) => { replies.push(text); },
  });
  const event = (id = "m1", text = "查看时钟", owner = "owner", time = "1000000", type = "p2p") => ({
    sender: { sender_type: "user", sender_id: { open_id: owner } },
    message: { message_id: id, create_time: time, chat_type: type, message_type: "text", content: JSON.stringify({ text }) },
  });
  return { controller, event, replies, calls: () => calls, stops: () => stops };
}

test("authorized command returns promptly and duplicate event executes once", async () => {
  let release!: (value: string) => void;
  const s = setup(() => new Promise<string>((resolve) => { release = resolve; }));
  await s.controller.receive(s.event());
  await s.controller.receive(s.event());
  await s.controller.receive(s.event("m2"));
  assert.equal(s.calls(), 1);
  assert.equal(s.controller.busy, true);
  release("clock result");
  await s.controller.pending;
  assert.equal(s.controller.busy, false);
  assert.equal(s.replies.at(-1), "clock result");
});

test("unknown users, group messages and stale events cannot control device", async () => {
  const s = setup();
  await s.controller.receive(s.event("m1", "查看时钟", "stranger"));
  await s.controller.receive(s.event("m2", "查看时钟", "owner", "1"));
  await s.controller.receive(s.event("m3", "查看时钟", "owner", "1000000", "group"));
  assert.equal(s.calls(), 0);
  assert.equal(s.replies.length, 0);
});

test("stop uses separate control path and never starts a task", async () => {
  const s = setup();
  await s.controller.receive(s.event("m1", "停止"));
  assert.equal(s.stops(), 1);
  assert.equal(s.calls(), 0);
});

test("task failure requests stop and does not replay accepted message", async () => {
  const s = setup(async () => { throw new Error("failure"); });
  await s.controller.receive(s.event());
  await s.controller.pending;
  await s.controller.receive(s.event());
  assert.equal(s.calls(), 1);
  assert.equal(s.stops(), 1);
  assert.equal(s.controller.busy, false);
});
