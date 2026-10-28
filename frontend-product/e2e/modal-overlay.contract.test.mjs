import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { createElement } from "react";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const root = fileURLToPath(new URL("..", import.meta.url));

// The portal target and mouse event are supplied doubles. No browser, HTTP
// server, interaction with the application or form submission is performed.
test("shared modal overlay preserves portal, presentation and background-only dismissal", async suite => {
  const server = await createServer({ configFile: false, root, plugins: [react()], server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: "custom", optimizeDeps: { noDiscovery: true, include: [] } });
  const previous = Object.getOwnPropertyDescriptor(globalThis, "document");
  let target = { nodeType: 1 };
  Object.defineProperty(globalThis, "document", { configurable: true, value: { getElementById(id) { assert.equal(id, "mk-modal-root"); return target; } } });
  try {
    assert.equal(server.httpServer, null);
    const { MarketingModalLayer } = await server.ssrLoadModule("/src/MarketingModal.tsx");
    const child = createElement("section", { role: "dialog" });
    for (const className of [undefined, "mk-product-overlay mk-wechat-qr-overlay", "mk-faq-overlay"]) {
      await suite.test(className ?? "default product overlay", () => {
        let closes = 0;
        let prevented = 0;
        const portal = MarketingModalLayer({ children: child, onClose: () => { closes += 1; }, className });
        assert.equal(portal.containerInfo, target, "The portal remains outside the inert page background");
        const overlay = portal.children;
        assert.equal(overlay.type, "div");
        assert.equal(overlay.props.className, className ?? "mk-product-overlay");
        assert.equal(overlay.props.role, "presentation");
        assert.equal(overlay.props.children, child, "The dialog is not rewrapped inside another scroll or focus container");
        const background = {};
        overlay.props.onMouseDown({ target: {}, currentTarget: background, preventDefault() { prevented += 1; } });
        assert.equal(closes, 0, "A press inside the dialog must not dismiss it");
        assert.equal(prevented, 0, "Native child input interactions remain available");
        overlay.props.onMouseDown({ target: background, currentTarget: background, preventDefault() { prevented += 1; } });
        assert.equal(prevented, 1, "Background dismissal prevents the underlying default action");
        assert.equal(closes, 1, "A direct press on the backdrop dismisses once");
      });
    }
    await suite.test("a missing modal root produces no detached overlay", () => {
      target = null;
      assert.equal(MarketingModalLayer({ children: child, onClose: () => assert.fail("No dismiss action should run during render") }), null);
    });
  } finally {
    if (previous) Object.defineProperty(globalThis, "document", previous);
    else delete globalThis.document;
    await server.close();
  }
});
