// Browser-compatible globals required by the tree-shaken Pi bundle but absent
// from the QuickJS standard library. Keep this prelude dependency-free: esbuild
// injects it before any imported module is initialized.
(() => {
  const runtime = globalThis;

  if (typeof runtime.TextEncoder !== "function") {
    runtime.TextEncoder = class TextEncoder {
      encode(input = "") {
        const value = String(input);
        const bytes = [];
        for (let index = 0; index < value.length; index += 1) {
          let codePoint = value.codePointAt(index);
          if (codePoint > 0xffff) index += 1;
          if (codePoint <= 0x7f) {
            bytes.push(codePoint);
          } else if (codePoint <= 0x7ff) {
            bytes.push(0xc0 | (codePoint >> 6), 0x80 | (codePoint & 0x3f));
          } else if (codePoint <= 0xffff) {
            bytes.push(
              0xe0 | (codePoint >> 12),
              0x80 | ((codePoint >> 6) & 0x3f),
              0x80 | (codePoint & 0x3f),
            );
          } else {
            bytes.push(
              0xf0 | (codePoint >> 18),
              0x80 | ((codePoint >> 12) & 0x3f),
              0x80 | ((codePoint >> 6) & 0x3f),
              0x80 | (codePoint & 0x3f),
            );
          }
        }
        return new Uint8Array(bytes);
      }
    };
  }

  if (typeof runtime.queueMicrotask !== "function") {
    runtime.queueMicrotask = (callback) => {
      void Promise.resolve().then(callback);
    };
  }

  if (typeof runtime.DOMException !== "function") {
    runtime.DOMException = class DOMException extends Error {
      constructor(message = "", name = "Error") {
        super(message);
        this.name = name;
      }
    };
  }

  if (typeof runtime.AbortController !== "function") {
    class MobileAbortSignal {
      constructor() {
        this.aborted = false;
        this.reason = undefined;
        this.listeners = new Set();
      }

      addEventListener(type, listener) {
        if (type === "abort") this.listeners.add(listener);
      }

      removeEventListener(type, listener) {
        if (type === "abort") this.listeners.delete(listener);
      }

      throwIfAborted() {
        if (this.aborted) throw this.reason ?? new runtime.DOMException("Aborted", "AbortError");
      }

      dispatchAbort(reason) {
        if (this.aborted) return;
        this.aborted = true;
        this.reason = reason ?? new runtime.DOMException("Aborted", "AbortError");
        for (const listener of this.listeners) listener.call(this, { type: "abort", target: this });
        this.listeners.clear();
      }
    }

    runtime.AbortSignal = MobileAbortSignal;
    runtime.AbortController = class AbortController {
      constructor() {
        this.signal = new MobileAbortSignal();
      }

      abort(reason) {
        this.signal.dispatchAbort(reason);
      }
    };
  }
})();
