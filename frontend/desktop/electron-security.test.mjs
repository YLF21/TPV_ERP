import { describe, expect, it, vi } from "vitest";
import {
  createPrivilegedIpcRegistrar,
  isAuthorizedIpcEvent,
  isLocalTrustedOrigin
} from "./electron-security.cjs";

function windowFor(origin = "http://127.0.0.1:43123") {
  const mainFrame = { url: origin };
  const webContents = { mainFrame };
  return { webContents, isDestroyed: () => false };
}

describe("Electron privileged IPC boundary", () => {
  it("only trusts loopback HTTP origins", () => {
    expect(isLocalTrustedOrigin("http://127.0.0.1:43123")).toBe(true);
    expect(isLocalTrustedOrigin("http://localhost:43123")).toBe(true);
    expect(isLocalTrustedOrigin("https://backend.example.test")).toBe(false);
    expect(isLocalTrustedOrigin("http://127.0.0.1:43123/?x=1")).toBe(false);
  });

  it("requires the expected window, its main frame, and matching origin", () => {
    const expected = windowFor();
    const event = { sender: expected.webContents, senderFrame: expected.webContents.mainFrame };
    expect(isAuthorizedIpcEvent(event, expected, "http://127.0.0.1:43123")).toBe(true);
    expect(isAuthorizedIpcEvent({ ...event, sender: {} }, expected, "http://127.0.0.1:43123")).toBe(false);
    expect(isAuthorizedIpcEvent({ ...event, senderFrame: { url: event.senderFrame.url } }, expected, "http://127.0.0.1:43123")).toBe(false);
    expect(isAuthorizedIpcEvent(event, expected, "http://127.0.0.1:43124")).toBe(false);
    expected.isDestroyed = () => true;
    expect(isAuthorizedIpcEvent(event, expected, "http://127.0.0.1:43123")).toBe(false);
  });

  it("wraps every registered handler and authorizes secondary windows by channel", async () => {
    const handlers = new Map();
    const ipcMain = { handle: vi.fn((channel, handler) => handlers.set(channel, handler)) };
    const main = windowFor();
    const secondary = windowFor();
    const register = createPrivilegedIpcRegistrar({ ipcMain, getTrustedOrigin: () => "http://127.0.0.1:43123" });
    register("main-only", () => main, (_event, value) => value + 1);
    register("secondary", () => secondary, (_event, value) => value + 1);
    expect(await handlers.get("main-only")({ sender: main.webContents, senderFrame: main.webContents.mainFrame }, 1)).toBe(2);
    expect((await handlers.get("main-only")({ sender: secondary.webContents, senderFrame: secondary.webContents.mainFrame }, 1)).code).toBe("IPC_UNAUTHORIZED");
    expect(await handlers.get("secondary")({ sender: secondary.webContents, senderFrame: secondary.webContents.mainFrame }, 1)).toBe(2);
  });

  it("supports a channel window set and a predicate without cross-authorizing windows", async () => {
    const handlers = new Map();
    const ipcMain = { handle: (_channel, handler) => handlers.set(_channel, handler) };
    const main = windowFor();
    const documentWindow = windowFor();
    const utilityWindow = windowFor();
    const register = createPrivilegedIpcRegistrar({ ipcMain, getTrustedOrigin: () => "http://127.0.0.1:43123" });
    register("document-print", () => [main, documentWindow], () => "document");
    register("utility-label", {
      windows: [main, utilityWindow],
      predicate: (candidate) => candidate === utilityWindow || candidate === main
    }, () => "utility");
    const invoke = (name, candidate) => handlers.get(name)({
      sender: candidate.webContents,
      senderFrame: candidate.webContents.mainFrame
    });
    expect(await invoke("document-print", documentWindow)).toBe("document");
    expect((await invoke("document-print", utilityWindow)).code).toBe("IPC_UNAUTHORIZED");
    expect(await invoke("utility-label", utilityWindow)).toBe("utility");
    expect((await invoke("utility-label", documentWindow)).code).toBe("IPC_UNAUTHORIZED");
  });
});
