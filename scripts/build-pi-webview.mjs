import { mkdir, readFile } from "node:fs/promises";
import path from "node:path";
import { build } from "esbuild";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const webViewOutputDirectory = path.join(repositoryRoot, "dist", "pi-webview");
const quickJsOutputDirectory = path.join(repositoryRoot, "dist", "pi-quickjs");
const quickJsPrelude = await readFile(
  path.join(repositoryRoot, "src", "pi", "mobile-quickjs-prelude.js"),
  "utf8",
);

await Promise.all([
  mkdir(webViewOutputDirectory, { recursive: true }),
  mkdir(quickJsOutputDirectory, { recursive: true }),
]);

const shared = {
  bundle: true,
  format: "iife",
  alias: {
    "@mariozechner/pi-ai": path.join(repositoryRoot, "src", "pi", "pi-ai-mobile-shim.ts"),
  },
  sourcemap: false,
  minify: false,
  legalComments: "inline",
};

await Promise.all([
  build({
    ...shared,
    entryPoints: [path.join(repositoryRoot, "src", "pi", "mobile-webview-entry.ts")],
    outfile: path.join(webViewOutputDirectory, "pi-mobile-runtime.js"),
    platform: "browser",
    target: ["chrome100", "safari15"],
  }),
  build({
    ...shared,
    entryPoints: [path.join(repositoryRoot, "src", "pi", "mobile-quickjs-entry.ts")],
    outfile: path.join(quickJsOutputDirectory, "pi-mobile-quickjs-runtime.js"),
    platform: "browser",
    target: ["es2020"],
    banner: { js: quickJsPrelude },
  }),
]);

const quickJsBundle = await readFile(
  path.join(quickJsOutputDirectory, "pi-mobile-quickjs-runtime.js"),
  "utf8",
);
for (const forbidden of ["window.", "document.", "MobileAgentNative", ".webkit"]) {
  if (quickJsBundle.includes(forbidden)) {
    throw new Error(`QuickJS bundle contains browser bridge dependency: ${forbidden}`);
  }
}
