import assert from "node:assert/strict";
import { it as test, vi } from "vitest";
import { EventEmitter } from "node:events";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { selectCustomerDisplay, createCustomerDisplayController } = require("./customer-display-controller.cjs");

const primary = { id: 1, bounds: { x: 0, y: 0, width: 1920, height: 1080 } };
const secondary = { id: 2, bounds: { x: 1920, y: 0, width: 800, height: 480 } };

function fixture(options = {}) {
  const instances = [];
  const sent = [];
  const screen = Object.assign(new EventEmitter(), {
    getAllDisplays: () => [primary, secondary], getPrimaryDisplay: () => primary,
  });
  class Window extends EventEmitter {
    constructor(options) {
      super(); this.options = options; this.updates = []; this.destroyed = false;
      this.webContents = Object.assign(new EventEmitter(), {
        setWindowOpenHandler: (callback) => { this.openHandler = callback; },
        executeJavaScript: async (source) => { this.updates.push(source); },
      });
      instances.push(this);
    }
    isDestroyed() { return this.destroyed; }
    destroy() { this.destroyed = true; this.emit("closed"); }
    async loadURL(url) { this.url = url; }
    showInactive() { this.shownInactive = true; }
    show() { this.shown = true; }
    setBounds(bounds) { this.bounds = bounds; }
  }
  const controller = createCustomerDisplayController({ BrowserWindow: Window, screen,
    renderHtml: () => "<html></html>", sendLineDisplay: async (config, state, options) => { sent.push({ config, state, options }); },
    ...options });
  controller.start();
  return { controller, instances, sent, screen };
}

test("never silently selects the cashier monitor or replaces a missing selection", () => {
  assert.equal(selectCustomerDisplay([primary, secondary], 1, "").id, 2);
  assert.throws(() => selectCustomerDisplay([primary, secondary], 1, "1"), /secundaria/);
  assert.throws(() => selectCustomerDisplay([primary, secondary], 1, "999"), /secundaria/);
  assert.throws(() => selectCustomerDisplay([primary], 1, ""), /secundaria/);
});

test("does not access Electron screen until explicitly started after app ready", () => {
  let ready = false;
  const events = [];
  const deferredScreen = new Proxy({}, { get: (_target, key) => {
    assert.equal(ready, true, "screen accessed before app ready");
    if (key === "on") return (event) => events.push(event);
    throw new Error(`Unexpected screen property ${String(key)}`);
  } });
  const controller = createCustomerDisplayController({ screen: deferredScreen });
  assert.equal(events.length, 0);
  ready = true;
  controller.start();
  controller.start();
  assert.deepEqual(events, ["display-removed", "display-metrics-changed"]);
});

test("disabled updates create no window; enabled updates reuse one passive window", async () => {
  const { controller, instances } = fixture();
  await controller.update({ customerDisplayEnabled: false }, { line1: "", line2: "" });
  assert.equal(instances.length, 0);
  await controller.update({ customerDisplayEnabled: true }, { line1: "First", line2: "" });
  await controller.update({ customerDisplayEnabled: true }, { line1: "Second", line2: "" });
  assert.equal(instances.length, 1);
  assert.equal(instances[0].options.focusable, false);
  assert.equal(instances[0].shownInactive, true);
  assert.equal(instances[0].updates.length, 1);
  assert.match(instances[0].updates[0], /Second/);
  assert.deepEqual(instances[0].openHandler(), { action: "deny" });
});

test("a disconnected secondary closes the customer window instead of moving it to primary", async () => {
  const { controller, instances, screen } = fixture();
  await controller.open({ customerDisplayScreenId: "2" }, { line1: "", line2: "" });
  screen.getAllDisplays = () => [primary];
  await assert.rejects(controller.update({ customerDisplayEnabled: true }, {}), /secundaria/);
  assert.equal(instances[0].destroyed, true);
});

test("physical mode uses its own transport and clears it when disabled", async () => {
  const { controller, instances, sent } = fixture();
  const config = { customerDisplayEnabled: true, customerDisplayMode: "LINE_DISPLAY" };
  await controller.update(config, { line1: "Product", line2: "TOTAL 10" });
  assert.equal(instances.length, 0);
  assert.equal(sent[0].state.line1, "Product");
  await controller.configurationChanged({ ...config, customerDisplayEnabled: false });
  assert.equal(sent.at(-1).state.line1, "");
  await controller.update({ customerDisplayEnabled: false }, { line1: "Ignored" });
  assert.equal(sent.length, 2);
});

test("monitor removal and primary changes close an idle view immediately without another update", async () => {
  for (const event of ["display-removed", "display-metrics-changed"]) {
    const { controller, instances, screen } = fixture();
    await controller.open({ customerDisplayScreenId: "2" }, { phase: "IDLE" });
    if (event === "display-removed") screen.getAllDisplays = () => [primary];
    else screen.getPrimaryDisplay = () => secondary;
    screen.emit(event);
    assert.equal(instances[0].destroyed, true);
  }
});

test("monitor size changes keep the customer view inside its current safe destination", async () => {
  const { controller, instances, screen } = fixture();
  await controller.open({ customerDisplayScreenId: "2" }, { phase: "IDLE" });
  const resized = { ...secondary, bounds: { ...secondary.bounds, width: 1024, height: 600 } };
  screen.getAllDisplays = () => [primary, resized];
  screen.emit("display-metrics-changed");
  assert.equal(instances[0].destroyed, false);
  assert.deepEqual(instances[0].bounds, resized.bounds);
});

test("a cashier window moved to a secondary screen cannot be covered by the client view", async () => {
  let operatorId = 1;
  const { controller, instances } = fixture({ getOperatorDisplayId: () => operatorId });
  await controller.open({ customerDisplayScreenId: "2" }, { phase: "IDLE" });
  operatorId = 2;
  controller.checkDisplays();
  assert.equal(instances[0].destroyed, true);
  await assert.rejects(controller.open({ customerDisplayScreenId: "2" }, {}), /secundaria/);
});

test("switching mode ignores an unplugged old device but still reports new transport failures", async () => {
  let unplugged = false;
  const { controller, instances } = fixture({ sendLineDisplay: async () => {
    if (unplugged) throw new Error("Disconnected physical display");
  } });
  await controller.open({ customerDisplayMode: "LINE_DISPLAY" }, { line1: "Ready" });
  unplugged = true;
  await controller.open({ customerDisplayMode: "DIGITAL_SCREEN", customerDisplayScreenId: "2" }, {});
  assert.equal(instances[0].shownInactive, true);
  await assert.rejects(controller.open({ customerDisplayMode: "LINE_DISPLAY" }, {}), /Disconnected/);
});

test("windowed preview is resizable and keeps the operator's bounds during sale and monitor updates", async () => {
  const { controller, instances, screen } = fixture({ windowed: true });
  const config = { customerDisplayEnabled: true, customerDisplayScreenId: "2" };
  await controller.open(config, { phase: "IDLE", line1: "Welcome", line2: "Thank you" });
  const view = instances[0];
  assert.equal(view.options.fullscreen, false);
  assert.equal(view.options.frame, true);
  assert.equal(view.options.resizable, true);
  assert.equal(view.options.focusable, true);
  assert.equal(view.options.skipTaskbar, false);
  assert.equal(view.shown, true);
  assert.ok(view.options.width < secondary.bounds.width);
  assert.ok(view.options.height < secondary.bounds.height);
  const chosenBounds = { x: 1950, y: 30, width: 480, height: 800 };
  view.setBounds(chosenBounds);
  await controller.update(config, { phase: "SALE", line1: "Article" });
  screen.emit("display-metrics-changed");
  await controller.configurationChanged({ ...config, customerDisplayIdleLine1: "New welcome" });
  assert.equal(instances.length, 1);
  assert.deepEqual(view.bounds, chosenBounds);
});

test("a framed preview can be used on the only available monitor", async () => {
  const { controller, instances, screen } = fixture({ windowed: true });
  screen.getAllDisplays = () => [primary];
  await controller.open({ customerDisplayEnabled: true }, { phase: "IDLE" });
  assert.equal(instances[0].shown, true);
  assert.equal(instances[0].options.fullscreen, false);
});

const longNameState = {
  phase: "SALE", line1: "A complete product name longer than twenty columns", line2: "Old sale total",
  sale: { lastItemId: "item", status: "READY", items: [{ id: "item",
    name: "A complete product name longer than twenty columns", quantity: 2, unitPrice: 1, total: 2 }], total: 2 },
};
const physicalConfig = { customerDisplayMode: "LINE_DISPLAY", customerDisplayColumns: 20 };

test("long names scroll without reloading the price, and a new article or idle cancels old frames", async () => {
  vi.useFakeTimers();
  try {
    const { controller, sent } = fixture();
    await controller.open(physicalConfig, longNameState);
    assert.equal(sent.length, 1);
    await vi.advanceTimersByTimeAsync(1500);
    assert.equal(sent.length, 2);
    assert.deepEqual(sent[1].options, { nameOffset: 1, firstLineOnly: true });
    const next = { ...longNameState, sale: { ...longNameState.sale,
      items: [{ ...longNameState.sale.items[0], name: "New article", quantity: 1 }] } };
    await controller.update(physicalConfig, next);
    const afterNewItem = sent.length;
    await vi.advanceTimersByTimeAsync(10000);
    assert.equal(sent.length, afterNewItem);
    assert.equal(sent.at(-1).state.sale.items[0].name, "New article");
    await controller.open(physicalConfig, longNameState);
    await controller.update(physicalConfig, { phase: "IDLE", line1: "Welcome", line2: "Thank you" });
    const afterIdle = sent.length;
    await vi.advanceTimersByTimeAsync(10000);
    assert.equal(sent.length, afterIdle);
    await controller.close();
  } finally { vi.useRealTimers(); }
});

test("slow scrolling writes do not accumulate and closing cancels the next frame", async () => {
  vi.useFakeTimers();
  try {
    let finishFrame;
    const sent = [];
    const { controller } = fixture({ sendLineDisplay: async (_config, state, options) => {
      sent.push({ state, options });
      if (options?.firstLineOnly) await new Promise(resolve => { finishFrame = resolve; });
    } });
    await controller.open(physicalConfig, longNameState);
    await vi.advanceTimersByTimeAsync(1500);
    assert.equal(sent.length, 2);
    await vi.advanceTimersByTimeAsync(10000);
    assert.equal(sent.length, 2);
    const closing = controller.close();
    finishFrame();
    await closing;
    assert.equal(sent.at(-1).state.line1, "");
    const afterClosing = sent.length;
    await vi.advanceTimersByTimeAsync(10000);
    assert.equal(sent.length, afterClosing);
  } finally { vi.useRealTimers(); }
});
