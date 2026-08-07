import assert from "node:assert/strict";
import { test } from "node:test";
import { prepareAdbArgs, quoteRemoteShellArg } from "../src/android/adb.js";

test("remote shell arguments are single-quoted safely", () => {
  assert.equal(quoteRemoteShellArg("plain"), "'plain'");
  assert.equal(quoteRemoteShellArg("a'b"), "'a'\"'\"'b'");
  assert.equal(
    prepareAdbArgs(["shell", "am", "start", "--es", "message", "$(touch /data/local/tmp/pwned)"])[1],
    "'am' 'start' '--es' 'message' '$(touch /data/local/tmp/pwned)'",
  );
});

test("non-shell ADB commands preserve argument boundaries", () => {
  assert.deepEqual(prepareAdbArgs(["exec-out", "screencap", "-p"]), ["exec-out", "screencap", "-p"]);
});
