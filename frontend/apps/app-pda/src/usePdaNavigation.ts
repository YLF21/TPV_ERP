import { useCallback, useEffect, useState } from "react";

export type PdaView = "home" | "check" | "lookup" | "replenishment" | "count" | "history" | "work";

export const PDA_HISTORY_STATE_KEY = "tpvPdaView";

const views = new Set<PdaView>(["home", "check", "lookup", "replenishment", "count", "history", "work"]);

function viewFromHistoryState(state: unknown): PdaView {
  if (!state || typeof state !== "object") return "home";
  const value = (state as Record<string, unknown>)[PDA_HISTORY_STATE_KEY];
  return typeof value === "string" && views.has(value as PdaView) ? value as PdaView : "home";
}

function stateForView(view: PdaView): Record<string, unknown> {
  const current = window.history.state;
  return {
    ...(current && typeof current === "object" ? current : {}),
    [PDA_HISTORY_STATE_KEY]: view
  };
}

export function usePdaNavigation() {
  const [view, setView] = useState<PdaView>("home");

  useEffect(() => {
    window.history.replaceState(stateForView("home"), "");

    function handlePopState(event: PopStateEvent) {
      setView(viewFromHistoryState(event.state));
    }

    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  const openView = useCallback((nextView: PdaView) => {
    if (view === nextView) return;
    window.history.pushState(stateForView(nextView), "");
    setView(nextView);
  }, [view]);

  const goHome = useCallback(() => {
    if (view === "home") return;
    if (viewFromHistoryState(window.history.state) === view) {
      window.history.back();
      return;
    }
    window.history.replaceState(stateForView("home"), "");
    setView("home");
  }, [view]);

  return { view, openView, goHome };
}

/** Warn only when the browser is about to discard an open module. */
export function usePdaModuleExitWarning(active: boolean) {
  useEffect(() => {
    if (!active) return;
    function handleBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault();
      event.returnValue = "";
    }
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, [active]);
}
