import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from "react";
import type { CompanySummary } from "../../lib/types";
import { useWorkspaceLabels } from "../../i18n/workspace";
import { normalizeSearch } from "../lib";
import "./company-picker.css";

export function CompanyPicker({ companies, value, onChange, disabled = false, required = true, selectedLabel = "" }: {
  companies: CompanySummary[];
  value: string;
  onChange: (id: string) => void;
  disabled?: boolean;
  required?: boolean;
  /** Keeps a selected filter readable if the company list is temporarily unavailable. */
  selectedLabel?: string;
}) {
  const l = useWorkspaceLabels();
  const id = useId();
  const container = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [query, setQuery] = useState("");
  const [activeId, setActiveId] = useState("");
  const selected = companies.find(company => company.companyId === value);
  const search = editing ? normalizeSearch(query) : "";
  const allLabel = l("all");
  const options = useMemo(() => {
    const matches: Pick<CompanySummary, "companyId" | "companyName" | "taxId">[] = companies.filter(company => !search
      || normalizeSearch(`${company.companyName} · ${company.taxId}`).includes(search));
    return !required && !search ? [{ companyId: "", companyName: allLabel, taxId: "" }, ...matches] : matches;
  }, [companies, search, required, allLabel]);
  const active = options.find(company => company.companyId === activeId) ?? options[0];
  const expanded = open && !disabled;
  const listId = `${id}-options`;
  const optionId = (companyId: string) => `${id}-option-${companyId}`;
  const activeOptionId = expanded && active ? optionId(active.companyId) : undefined;
  const selectionRequired = l("selectCompany");

  useEffect(() => {
    if (value || !required) { setEditing(false); setQuery(""); }
  }, [value, required]);
  useEffect(() => {
    if (disabled) setOpen(false);
  }, [disabled]);
  useEffect(() => {
    // A typed name is not a selected company, even when it exactly matches one.
    input.current?.setCustomValidity(!required || (selected && !editing) ? "" : selectionRequired);
  }, [selected, editing, selectionRequired, required]);
  useEffect(() => {
    if (!expanded) return;
    const closeOutside = (event: PointerEvent) => {
      if (event.target instanceof Node && !container.current?.contains(event.target)) {
        setOpen(false);
        if (!required) { setEditing(false); setQuery(""); }
      }
    };
    document.addEventListener("pointerdown", closeOutside, true);
    return () => document.removeEventListener("pointerdown", closeOutside, true);
  }, [expanded, required]);
  useEffect(() => {
    if (!activeOptionId || !list.current) return;
    const option = document.getElementById(activeOptionId);
    if (!option) return;
    const viewport = list.current;
    if (option.offsetTop < viewport.scrollTop) viewport.scrollTop = option.offsetTop;
    else if (option.offsetTop + option.offsetHeight > viewport.scrollTop + viewport.clientHeight) {
      viewport.scrollTop = option.offsetTop + option.offsetHeight - viewport.clientHeight;
    }
  }, [activeOptionId]);

  function showOptions() {
    if (disabled) return;
    setActiveId(selected?.companyId ?? "");
    setOpen(true);
  }
  function closeOptions() {
    setOpen(false);
    if (!required) { setEditing(false); setQuery(""); }
  }
  function choose(company: Pick<CompanySummary, "companyId">) {
    if (disabled) return;
    setEditing(false);
    setQuery("");
    setActiveId(company.companyId);
    setOpen(false);
    onChange(company.companyId);
  }
  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.nativeEvent.isComposing) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const direction = event.key === "ArrowDown" ? 1 : -1;
      const index = options.findIndex(company => company.companyId === active?.companyId);
      const next = expanded ? Math.max(0, Math.min(options.length - 1, index + direction))
        : selected ? options.findIndex(company => company.companyId === selected.companyId)
          : direction > 0 ? 0 : options.length - 1;
      setActiveId(options[next]?.companyId ?? "");
      setOpen(true);
    } else if (event.key === "Enter") {
      // Enter belongs to the picker while focused; it never submits the form.
      event.preventDefault();
      event.stopPropagation();
      if (expanded && active) choose(active);
      else showOptions();
    } else if (event.key === "Escape" && expanded) {
      event.preventDefault();
      event.stopPropagation();
      closeOptions();
    } else if (event.key === "Tab") closeOptions();
  }

  return <div ref={container} className="saas-company-picker" onBlur={event => {
    if (!event.currentTarget.contains(event.relatedTarget as Node | null)) closeOptions();
  }}>
    <label htmlFor={id}>{l("company")}</label>
    <div className="saas-company-picker-control">
      <input ref={input} id={id} className="control-input" role="combobox" type="text" autoComplete="off"
        value={editing ? query : selected ? `${selected.companyName} · ${selected.taxId}` : value ? selectedLabel : ""}
        placeholder={required ? selectionRequired : allLabel} disabled={disabled} required={required} aria-required={required}
        aria-autocomplete="list" aria-haspopup="listbox" aria-expanded={expanded} aria-controls={listId}
        aria-activedescendant={activeOptionId} onFocus={showOptions} onClick={showOptions}
        onKeyDown={handleKeyDown} onChange={event => {
          setQuery(event.target.value); setEditing(true); setActiveId(""); setOpen(true);
          // Searching a filter must not remove the currently applied company.
          if (required) onChange("");
        }} />
      <button type="button" className="saas-company-picker-trigger" tabIndex={-1} disabled={disabled}
        aria-label={selectionRequired} aria-controls={listId} aria-expanded={expanded}
        onMouseDown={event => event.preventDefault()} onClick={() => {
          if (expanded) closeOptions();
          else { input.current?.focus(); showOptions(); }
        }}><span aria-hidden="true">▾</span></button>
    </div>
    {expanded && <div className="saas-company-picker-popup">
      <ul ref={list} id={listId} role="listbox" aria-label={l("company")}>
        {options.map(company => <li id={optionId(company.companyId)} key={company.companyId} role="option"
          aria-selected={value === company.companyId}
          className={active?.companyId === company.companyId ? "is-active" : ""}
          onMouseDown={event => event.preventDefault()} onMouseMove={() => setActiveId(company.companyId)}
          onClick={() => choose(company)}>
          <span>{company.companyName}</span>{company.taxId && <small>{company.taxId}</small>}
        </li>)}
      </ul>
      {options.length === 0 && <p className="saas-company-picker-empty" role="status">{l("empty")}</p>}
    </div>}
  </div>;
}
