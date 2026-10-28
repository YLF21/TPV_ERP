import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

// Method-level element/ref doubles exercise the real modal key handler. This
// is not a DOM or a simulation of native browser tab order. Cua separately
// verifies the browser's actual next focus target after the native hand-off.
test("modal Tab trapping preserves native navigation from an internal programmatic focus target", async () => {
  const effects = [];
  const globals = ["document", "HTMLElement", "Node", "__modalKeyboardEffects"];
  const previous = new Map(globals.map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  let activeDocument;
  class ElementDouble {
    constructor(name, tabIndex = 0) {
      this.name = name;
      this.tabIndex = tabIndex;
      this.parent = null;
      this.isConnected = true;
      this.style = { overflow: "auto" };
      this.inert = false;
      this.attributes = new Map();
      this.ownerDocument = { defaultView: { getComputedStyle: () => ({ display: "block", visibility: "visible" }) } };
    }
    focus() { activeDocument.activeElement = this; }
    contains(node) { for (let current = node; current; current = current.parent) if (current === this) return true; return false; }
    closest(selector) { return selector === ".mk-page" ? page : null; }
    matches() { return false; }
    getClientRects() { return [{}]; }
    getAttribute(name) { return this.attributes.get(name) ?? null; }
    setAttribute(name, value) { this.attributes.set(name, value); }
    removeAttribute(name) { this.attributes.delete(name); }
  }
  const page = new ElementDouble("page");
  const background = new ElementDouble("background");
  const main = new ElementDouble("main", -1);
  const dialog = new ElementDouble("dialog", -1);
  const close = new ElementDouble("close");
  const tab = new ElementDouble("application tab");
  const stage = new ElementDouble("stage");
  const heading = new ElementDouble("step heading", -1);
  const enlarge = new ElementDouble("enlarge capture");
  const next = new ElementDouble("next");
  const outside = new ElementDouble("outside");
  dialog.parent = page;
  for (const element of [close, tab, stage, enlarge, next]) element.parent = dialog;
  heading.parent = stage;
  dialog.querySelectorAll = () => [close, tab, stage, heading, enlarge, next];
  page.querySelector = selector => selector === "[data-marketing-background]" ? background : main;
  const listeners = new Map();
  activeDocument = {
    activeElement: main,
    body: new ElementDouble("body"), documentElement: new ElementDouble("html"),
    addEventListener: (type, handler) => listeners.set(type, handler),
    removeEventListener: (type, handler) => { if (listeners.get(type) === handler) listeners.delete(type); }
  };
  for (const [key, value] of Object.entries({ document: activeDocument, HTMLElement: ElementDouble, Node: ElementDouble, __modalKeyboardEffects: effects })) {
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  }
  const server = await createServer({
    configFile: false, root: fileURLToPath(new URL("..", import.meta.url)),
    plugins: [{
      name: "controlled-modal-hooks", enforce: "pre",
      resolveId(id) { if (id === "modal-keyboard-hooks") return "\0modal-keyboard-hooks"; },
      load(id) {
        if (id === "\0modal-keyboard-hooks") return "export const useRef = value => ({current:value}); export const useEffect = effect => globalThis.__modalKeyboardEffects.push(effect);";
      },
      transform(code, id) {
        if (!id.replaceAll("\\", "/").endsWith("/src/MarketingModal.tsx")) return;
        return code.replace('from "react";', 'from "modal-keyboard-hooks";');
      }
    }, react()],
    server: { middlewareMode: true, hmr: false, ws: false, watch: null },
    appType: "custom", optimizeDeps: { noDiscovery: true, include: [] }
  });
  let cleanup;
  try {
    assert.equal(server.httpServer, null);
    const { useModalDialog } = await server.ssrLoadModule("/src/MarketingModal.tsx");
    let closed = 0;
    const refs = useModalDialog(() => { closed += 1; });
    refs.dialogRef.current = dialog;
    refs.closeRef.current = close;
    effects[0]();
    cleanup = effects[1]();
    assert.equal(activeDocument.activeElement, close, "Opening still places focus on the close control");
    assert.equal(background.inert, true);

    function press(target, shiftKey = false, key = "Tab") {
      activeDocument.activeElement = target;
      const event = { key, shiftKey, prevented: false, preventDefault() { this.prevented = true; } };
      listeners.get("keydown")(event);
      return { prevented: event.prevented, active: activeDocument.activeElement };
    }
    for (const shift of [false, true]) {
      assert.deepEqual(press(heading, shift), { prevented: false, active: heading }, "Tab in either direction from an internal h3 is left to the browser");
      assert.deepEqual(press(stage, shift), { prevented: false, active: stage }, "An interior ordinary tab stop retains native order");
      assert.deepEqual(press(dialog, shift), { prevented: true, active: shift ? next : close }, "The dialog itself still enters its tab sequence");
      assert.deepEqual(press(outside, shift), { prevented: true, active: shift ? next : close }, "An external focus still enters the trap");
    }
    assert.deepEqual(press(close, true), { prevented: true, active: next }, "Shift+Tab wraps from the first control");
    assert.deepEqual(press(next), { prevented: true, active: close }, "Tab wraps from the last control");
    assert.deepEqual(press(close), { prevented: false, active: close });
    assert.deepEqual(press(next, true), { prevented: false, active: next });
    assert.deepEqual(press(heading, false, "Escape"), { prevented: true, active: heading });
    assert.equal(closed, 1);
    listeners.get("focusin")({ target: outside });
    assert.equal(activeDocument.activeElement, close, "Unexpected external focus is still recovered");
    cleanup();
    cleanup = undefined;
    assert.equal(background.inert, false);
    assert.equal(activeDocument.activeElement, main, "Closing restores the previous focus");
    assert.equal(listeners.size, 0);
  } finally {
    cleanup?.();
    await server.close();
    for (const [key, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
  }
});
