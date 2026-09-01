// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PDA_HISTORY_STATE_KEY, usePdaModuleExitWarning, usePdaNavigation } from "./usePdaNavigation";

function NavigationHarness() {
  const navigation = usePdaNavigation();
  usePdaModuleExitWarning(navigation.view !== "home");
  return <>
    <output aria-label="vista">{navigation.view}</output>
    <button type="button" onClick={() => navigation.openView("lookup")}>Abrir consulta</button>
    <button type="button" onClick={navigation.goHome}>Volver</button>
  </>;
}

describe("usePdaNavigation", () => {
  beforeEach(() => window.history.replaceState(null, ""));
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("creates a history entry for a module and follows Android/browser Back", () => {
    render(<NavigationHarness />);
    expect(screen.getByLabelText("vista").textContent).toBe("home");
    expect(window.history.state[PDA_HISTORY_STATE_KEY]).toBe("home");

    fireEvent.click(screen.getByRole("button", { name: "Abrir consulta" }));
    expect(screen.getByLabelText("vista").textContent).toBe("lookup");
    expect(window.history.state[PDA_HISTORY_STATE_KEY]).toBe("lookup");

    act(() => window.dispatchEvent(new PopStateEvent("popstate", {
      state: { [PDA_HISTORY_STATE_KEY]: "home" }
    })));
    expect(screen.getByLabelText("vista").textContent).toBe("home");
  });

  it("uses browser history when the visible Back button closes a module", () => {
    const back = vi.spyOn(window.history, "back").mockImplementation(() => undefined);
    render(<NavigationHarness />);
    fireEvent.click(screen.getByRole("button", { name: "Abrir consulta" }));
    fireEvent.click(screen.getByRole("button", { name: "Volver" }));
    expect(back).toHaveBeenCalledOnce();
  });

  it("does not duplicate history entries under React StrictMode", () => {
    const pushState = vi.spyOn(window.history, "pushState");
    render(<StrictMode><NavigationHarness /></StrictMode>);
    fireEvent.click(screen.getByRole("button", { name: "Abrir consulta" }));
    expect(pushState).toHaveBeenCalledOnce();
  });

  it("warns before the browser discards an open module", () => {
    render(<NavigationHarness />);
    const atHome = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(atHome);
    expect(atHome.defaultPrevented).toBe(false);

    fireEvent.click(screen.getByRole("button", { name: "Abrir consulta" }));
    const inModule = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(inModule);
    expect(inModule.defaultPrevented).toBe(true);
  });
});
