import { useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent } from "react";
import { createPortal } from "react-dom";
import { apiRequest } from "../api/client";
import type { UserSession } from "../types";
import "./PartyAddressAutocomplete.css";

export type SuggestedPartyAddress = {
  address: string; postalCode: string; city: string; province: string; country: string;
};
type Suggestion = { id: string; type: string; label: string };
type Props = {
  value: string;
  onChange: (value: string) => void;
  onSelect: (address: SuggestedPartyAddress) => void;
  context: { postalCode: string; city: string; country: string };
  scopeKey: string;
  session: UserSession;
  disabled: boolean;
  invalid: boolean;
  label: string;
  t: (key: string) => string;
};
type Status = "loading" | "unavailable" | "empty" | "selecting" | null;

export function PartyAddressAutocomplete({ value, onChange, onSelect, context, scopeKey, session, disabled, invalid, label, t }: Props) {
  const listId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const popupRef = useRef<HTMLDivElement>(null);
  const request = useRef<{ id: number; controller?: AbortController; timeout?: ReturnType<typeof setTimeout> }>({ id: 0 });
  const [editedValue, setEditedValue] = useState<string | null>(null);
  const [items, setItems] = useState<Suggestion[]>([]);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [status, setStatus] = useState<Status>(null);
  const identity = JSON.stringify([value, context.postalCode, context.city, context.country, scopeKey, disabled, session.accessToken]);
  const latestIdentity = useRef(identity);
  latestIdentity.current = identity;
  const eligible = !disabled && ["", "ES"].includes(context.country.trim().toUpperCase());
  const visible = open && eligible;

  function cancel() {
    request.current.id += 1;
    request.current.controller?.abort();
    clearTimeout(request.current.timeout);
  }

  function close() {
    cancel();
    setOpen(false);
    setEditedValue(null);
  }

  // A generation and the complete field identity protect against transports that
  // complete after abort, including a resolution for a different address/record.
  function startRequest() {
    cancel();
    const id = request.current.id;
    const capturedIdentity = identity;
    const controller = new AbortController();
    request.current.controller = controller;
    const current = () => id === request.current.id && capturedIdentity === latestIdentity.current && !controller.signal.aborted;
    request.current.timeout = setTimeout(() => {
      if (!current()) return;
      cancel();
      setItems([]);
      setStatus("unavailable");
    }, 5000);
    return { current, signal: controller.signal, finish: () => clearTimeout(request.current.timeout) };
  }

  useEffect(() => {
    cancel();
    setItems([]);
    setActive(-1);
    setStatus(null);
    if (!eligible || editedValue !== value || value.trim().length < 3) {
      setOpen(false);
      return;
    }
    setOpen(true);
    const debounce = setTimeout(() => {
      const pending = startRequest();
      setStatus("loading");
      const params = new URLSearchParams({ q: value.trim() });
      void apiRequest<{ available: boolean; items: Suggestion[] }>(`/party-addresses/suggestions?${params}`, {
        token: session.accessToken, signal: pending.signal,
      }).then(result => {
        if (!pending.current()) return;
        pending.finish();
        setItems(result.available ? result.items : []);
        setStatus(!result.available ? "unavailable" : result.items.length ? null : "empty");
      }).catch(() => {
        if (!pending.current()) return;
        pending.finish();
        setStatus("unavailable");
      });
    }, 300);
    return () => { clearTimeout(debounce); cancel(); };
  }, [identity, editedValue]);

  function choose(item: Suggestion) {
    if (!eligible || status === "selecting") return;
    const pending = startRequest();
    setStatus("selecting");
    const params = new URLSearchParams({ id: item.id, type: item.type });
    void apiRequest<{ available: boolean; address: SuggestedPartyAddress | null }>(`/party-addresses/resolve?${params}`, {
      token: session.accessToken, signal: pending.signal,
    }).then(result => {
      if (!pending.current()) return;
      pending.finish();
      if (!result.available || !result.address) { setStatus("unavailable"); setItems([]); return; }
      close();
      onSelect(result.address);
    }).catch(() => {
      if (!pending.current()) return;
      pending.finish();
      setItems([]);
      setStatus("unavailable");
    });
  }

  useLayoutEffect(() => {
    if (!visible) return;
    function position() {
      const input = inputRef.current?.getBoundingClientRect();
      const popup = popupRef.current;
      if (!input || !popup) return;
      const below = window.innerHeight - input.bottom - 8;
      const above = input.top - 8;
      const useAbove = below < 180 && above > below;
      const width = Math.max(0, Math.min(input.width, window.innerWidth - 16));
      popup.style.left = `${Math.max(8, Math.min(input.left, window.innerWidth - width - 8))}px`;
      popup.style.width = `${width}px`;
      popup.style.maxHeight = `${Math.max(0, Math.min(260, (useAbove ? above : below) - 4))}px`;
      popup.style.top = useAbove ? "auto" : `${input.bottom + 3}px`;
      popup.style.bottom = useAbove ? `${window.innerHeight - input.top + 3}px` : "auto";
    }
    position();
    const observer = typeof ResizeObserver === "undefined" ? undefined : new ResizeObserver(position);
    if (inputRef.current) observer?.observe(inputRef.current);
    window.addEventListener("resize", position);
    window.addEventListener("scroll", position, true);
    return () => { observer?.disconnect(); window.removeEventListener("resize", position); window.removeEventListener("scroll", position, true); };
  }, [visible, items.length, status]);

  useEffect(() => {
    if (!visible) return;
    function outside(event: PointerEvent) {
      if (event.target instanceof Node && !inputRef.current?.contains(event.target) && !popupRef.current?.contains(event.target)) close();
    }
    document.addEventListener("pointerdown", outside, true);
    return () => document.removeEventListener("pointerdown", outside, true);
  }, [visible]);

  useEffect(() => {
    popupRef.current?.querySelector<HTMLElement>('[data-active="true"]')?.scrollIntoView?.({ block: "nearest" });
  }, [active]);

  function keyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (!visible) return;
    if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); close(); }
    if (event.key === "Enter") {
      event.preventDefault(); event.stopPropagation();
      if (items[active]) choose(items[active]);
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (items.length) setActive(index => index < 0 ? (event.key === "ArrowDown" ? 0 : items.length - 1)
        : (index + (event.key === "ArrowDown" ? 1 : -1) + items.length) % items.length);
    }
    if (event.key === "Tab") close();
  }

  return <div className="party-address-autocomplete">
    <input ref={inputRef} role="combobox" aria-label={label} aria-invalid={invalid} maxLength={255}
      aria-expanded={visible} aria-controls={visible ? listId : undefined} aria-autocomplete="list"
      aria-activedescendant={visible && items[active] ? `${listId}-${active}` : undefined}
      disabled={disabled} autoComplete="off" value={value} onKeyDown={keyDown}
      onBlur={event => { if (!popupRef.current?.contains(event.relatedTarget as Node | null)) close(); }}
      onChange={event => { cancel(); setItems([]); setActive(-1); setEditedValue(event.target.value); onChange(event.target.value); }} />
    {visible && createPortal(<div ref={popupRef} className="party-address-autocomplete__popup"
      onBlur={event => { if (!popupRef.current?.contains(event.relatedTarget as Node | null) && event.relatedTarget !== inputRef.current) close(); }}>
      {status && <div className="party-address-autocomplete__status" role="status">{t(`party.addressSearch.${status}`)}</div>}
      <div role="listbox" id={listId} aria-label={t("party.addressSearch.results")} aria-busy={status === "loading" || status === "selecting"}>
        {items.map((item, index) => <button key={`${item.id}-${item.type}`} id={`${listId}-${index}`} type="button" role="option"
          aria-selected={active === index} data-active={active === index ? "true" : undefined} tabIndex={-1}
          disabled={disabled || status === "selecting"} onMouseDown={event => event.preventDefault()}
          onPointerMove={() => setActive(index)} onClick={() => choose(item)}>{item.label}</button>)}
      </div>
      {items.length > 0 && <a className="party-address-autocomplete__attribution" href="https://www.scne.es/productos.html#CartoCiudad"
        target="_blank" rel="noopener noreferrer" aria-label={t("party.addressSearch.attribution")}>CartoCiudad · CC BY 4.0</a>}
    </div>, document.body)}
  </div>;
}
