import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type Dispatch, type MouseEvent, type SetStateAction } from "react";

const values = new Map<string, unknown>();
type Position = { top: number; left: number; row: string; href: string };
const positions = new Map<string, Position>();
let sequence = 0;
// HTTP access on a private LAN does not expose crypto.randomUUID in every browser.
function newEntry() { return window.crypto?.randomUUID?.() ?? `${Date.now()}-${++sequence}`; }
function entry() {
  if (!window.history.state?.directoryEntry) window.history.replaceState({ ...window.history.state,
    directoryEntry: newEntry() }, "");
  return window.history.state.directoryEntry as string;
}
function subscribe(listener: () => void) {
  window.addEventListener("popstate", listener); window.addEventListener("hashchange", listener);
  return () => { window.removeEventListener("popstate", listener); window.removeEventListener("hashchange", listener); };
}

/** Keep each history entry's filters in this session, without saving credentials or business data to storage. */
export function useDirectoryState<T>(key: string, initial: T | (() => T)): [T, Dispatch<SetStateAction<T>>] {
  const identity = useSyncExternalStore(subscribe, entry) + ":" + key;
  const [, render] = useState(0);
  if (!values.has(identity)) {
    values.set(identity, typeof initial === "function" ? (initial as () => T)() : initial);
    if (values.size > 200) values.delete(values.keys().next().value!);
  }
  const setValue: Dispatch<SetStateAction<T>> = useCallback(next => {
    values.set(identity, typeof next === "function" ? (next as (previous: T) => T)(values.get(identity) as T) : next);
    render(value => value + 1);
  }, [identity]);
  return [values.get(identity) as T, setValue];
}

export function directoryLink(view: "companies" | "stores" | "licenses", parameters: Record<string, string> = {}) {
  const query = new URLSearchParams(parameters).toString();
  return `#/${view}${query ? "?" + query : ""}`;
}
export function followDirectoryLink(event: MouseEvent<HTMLAnchorElement>, href: string) {
  if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
  event.preventDefault();
  const table = document.querySelector<HTMLElement>(".table-workspace .saas-data-table-scroll");
  positions.set(entry(), { top: table?.scrollTop ?? 0, left: table?.scrollLeft ?? 0,
    row: event.currentTarget.closest("tr")?.getAttribute("data-row-id") ?? "",
    href: event.currentTarget.getAttribute("href") ?? "" });
  if (positions.size > 100) positions.delete(positions.keys().next().value!);
  window.history.pushState({ directoryEntry: newEntry(), directoryFrom: window.location.hash }, "", href);
  window.dispatchEvent(new HashChangeEvent("hashchange"));
}

/** Wait for paged rows to reappear, without overriding a subsequent user interaction. */
export function useDirectoryPosition(session: string) {
  const identity = useSyncExternalStore(subscribe, entry);
  const activeSession = useRef(session);
  useEffect(() => {
    if (activeSession.current !== session) positions.clear();
    activeSession.current = session;
    const position = positions.get(identity);
    if (!position) return;
    let stopped = false;
    const stop = () => { stopped = true; observer.disconnect(); resize.disconnect(); clearTimeout(timer); };
    const restore = () => {
      if (stopped) return;
      const table = document.querySelector<HTMLElement>(".table-workspace .saas-data-table-scroll");
      if (!table) return;
      resize.observe(table);
      if (table.firstElementChild) resize.observe(table.firstElementChild);
      table.scrollTo({ top: position.top, left: position.left });
      const row = position.row ? table.querySelector<HTMLElement>(`tr[data-row-id="${CSS.escape(position.row)}"]`) : null;
      const scope = row ?? document.querySelector("dialog[open]") ?? table;
      const link = [...scope.querySelectorAll<HTMLAnchorElement>("a[href]")].find(item => item.getAttribute("href") === position.href);
      const target = link ?? row;
      if (target && table.scrollTop >= position.top - 1) { target.focus({ preventScroll: true }); stop(); }
    };
    const observer = new MutationObserver(restore);
    const resize = new ResizeObserver(restore);
    const timer = setTimeout(stop, 10000);
    observer.observe(document.querySelector(".main-panel") ?? document.body, { childList: true, subtree: true });
    for (const name of ["pointerdown", "keydown", "wheel"]) window.addEventListener(name, stop, { once: true, passive: true });
    restore();
    return () => { stop(); for (const name of ["pointerdown", "keydown", "wheel"]) window.removeEventListener(name, stop); };
  }, [identity, session]);
}
