// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, render, screen } from "@testing-library/react";
import { useState, type Dispatch, type SetStateAction } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TerminalContext } from "./types";
import { STORE_CODE_REFRESH_MS, useTerminalStoreCode } from "./useTerminalStoreCode";

const initial: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "001",
  installationId: "installation-1",
  terminalId: "terminal-1",
  terminalCredential: "credential-1",
  bindingId: "binding-1"
};

let changeContext!: Dispatch<SetStateAction<TerminalContext | null | undefined>>;

function Harness({ context = initial }: { context?: TerminalContext }) {
  const [terminalContext, setTerminalContext] = useState<TerminalContext | null | undefined>(context);
  changeContext = setTerminalContext;
  useTerminalStoreCode(terminalContext, setTerminalContext);
  return <output data-testid="code">{terminalContext?.storeInternalCode ?? "missing"}</output>;
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("useTerminalStoreCode", () => {
  it("acquires a later protected code and stops polling once present", async () => {
    const load = vi.fn().mockResolvedValue({ ok: true, identity: { ...initial, storeInternalCode: "3500002" } });
    vi.stubGlobal("tpvDesktop", { terminalIdentity: { load } });
    render(<Harness />);
    expect(load).not.toHaveBeenCalled();
    await act(async () => { await vi.advanceTimersByTimeAsync(STORE_CODE_REFRESH_MS); });
    expect(screen.getByTestId("code")).toHaveTextContent("3500002");
    await act(async () => { await vi.advanceTimersByTimeAsync(STORE_CODE_REFRESH_MS * 2); });
    expect(load).toHaveBeenCalledTimes(1);
  });

  it.each([
    { ok: false, identity: { ...initial, storeInternalCode: "3500002" } },
    { ok: true, identity: null, displayContext: { storeInternalCode: "3500002" } },
    { ok: true, identity: { ...initial, storeInternalCode: "0000000" } },
    { ok: true, identity: { ...initial, terminalCredential: "other", storeInternalCode: "3500002" } }
  ])("does not trust unavailable, cached, invalid, or mismatched code: %j", async (result) => {
    const load = vi.fn().mockResolvedValue(result);
    vi.stubGlobal("tpvDesktop", { terminalIdentity: { load } });
    render(<Harness />);
    await act(async () => { await vi.advanceTimersByTimeAsync(STORE_CODE_REFRESH_MS); });
    expect(screen.getByTestId("code")).toHaveTextContent("missing");
  });

  it("ignores a late response after the terminal binding changes", async () => {
    let finish!: (result: { ok: true; identity: TerminalContext }) => void;
    const load = vi.fn().mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    vi.stubGlobal("tpvDesktop", { terminalIdentity: { load } });
    render(<Harness />);
    await act(async () => { await vi.advanceTimersByTimeAsync(STORE_CODE_REFRESH_MS); });
    await act(async () => { await vi.advanceTimersByTimeAsync(STORE_CODE_REFRESH_MS); });
    expect(load).toHaveBeenCalledTimes(1);
    const next = { ...initial, terminalId: "terminal-2", bindingId: "binding-2" };
    act(() => changeContext(next));
    await act(async () => { finish({ ok: true, identity: { ...initial, storeInternalCode: "3500002" } }); });
    expect(screen.getByTestId("code")).toHaveTextContent("missing");
    expect(load).toHaveBeenCalledTimes(1);
  });

  it("discards a response after unmount", async () => {
    let finish!: (result: { ok: true; identity: TerminalContext }) => void;
    const load = vi.fn().mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    vi.stubGlobal("tpvDesktop", { terminalIdentity: { load } });
    const view = render(<Harness />);
    await act(async () => { await vi.advanceTimersByTimeAsync(STORE_CODE_REFRESH_MS); });
    view.unmount();
    await act(async () => { finish({ ok: true, identity: { ...initial, storeInternalCode: "3500002" } }); });
    await act(async () => { await vi.advanceTimersByTimeAsync(STORE_CODE_REFRESH_MS); });
    expect(load).toHaveBeenCalledTimes(1);
  });

  it("does no work without a protected bridge, installation, credentials, or when code is present", async () => {
    const load = vi.fn();
    vi.stubGlobal("tpvDesktop", { terminalIdentity: { load } });
    const view = render(<Harness context={{ ...initial, installationId: undefined }} />);
    await act(async () => { await vi.advanceTimersByTimeAsync(STORE_CODE_REFRESH_MS); });
    act(() => changeContext({ ...initial, terminalCredential: undefined }));
    await act(async () => { await vi.advanceTimersByTimeAsync(STORE_CODE_REFRESH_MS); });
    act(() => changeContext({ ...initial, storeInternalCode: "3500002" }));
    await act(async () => { await vi.advanceTimersByTimeAsync(STORE_CODE_REFRESH_MS); });
    expect(load).not.toHaveBeenCalled();
    view.unmount();
    vi.stubGlobal("tpvDesktop", undefined);
    render(<Harness />);
    await act(async () => { await vi.advanceTimersByTimeAsync(STORE_CODE_REFRESH_MS); });
    expect(load).not.toHaveBeenCalled();
  });
});
