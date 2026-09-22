import { useEffect, useId, useMemo, useRef, useState } from "react";
import type { FocusEvent, KeyboardEvent } from "react";
import { erpSelectKeyIntent, erpSelectPopoverLayout, nextErpSelectOptionIndex, type ErpSelectOption } from "./ErpSelect";
import { useOutsidePointerDown } from "./useOutsidePointerDown";
import "./ErpMultiSelect.css";

export type ErpMultiSelectProps = {
  values: readonly string[];
  options: readonly ErpSelectOption[];
  onChange: (values: string[]) => void;
  placeholder: string;
  /** Enables searching the available options. */
  searchPlaceholder?: string;
  /** Allows an exact value absent from the options, with an explicit option label. */
  customValueLabel?: (value: string) => string;
  disabled?: boolean;
  id?: string;
  className?: string;
  title?: string;
  "aria-label"?: string;
  "aria-labelledby"?: string;
};

export function ErpMultiSelect({
  values, options, onChange, placeholder, searchPlaceholder, customValueLabel, disabled = false,
  id, className, title, "aria-label": ariaLabel, "aria-labelledby": ariaLabelledBy,
}: ErpMultiSelectProps) {
  const generatedId = useId();
  const listboxId = `${id ?? generatedId}-listbox`;
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(-1);
  const [focusTarget, setFocusTarget] = useState<"search" | "option">("option");
  const selectedValues = [...new Set(values.filter(Boolean))];
  const availableOptions = useMemo(() => {
    const seen = new Set<string>();
    return options.filter((option) => {
      if (seen.has(option.value)) return false;
      seen.add(option.value);
      return true;
    });
  }, [options]);
  const visibleOptions = useMemo(() => {
    const trimmedQuery = query.trim();
    const normalizedQuery = trimmedQuery.toLocaleLowerCase();
    const matching = availableOptions.filter((option) => !normalizedQuery
      || option.label.toLocaleLowerCase().includes(normalizedQuery));
    if (customValueLabel && trimmedQuery && !availableOptions.some(option => option.value === trimmedQuery)) {
      matching.push({ value: trimmedQuery, label: customValueLabel(trimmedQuery) });
    }
    return matching;
  }, [availableOptions, customValueLabel, query]);
  const selectedLabel = selectedValues.map((value) => availableOptions.find((option) => option.value === value)?.label ?? value).join(", ");
  const accessibleLabel = ariaLabel ?? placeholder;

  function closeMenu(restoreFocus = false) {
    setOpen(false);
    setQuery("");
    if (restoreFocus) triggerRef.current?.focus();
  }

  function openMenu(fromEnd = false, focusSearch = true) {
    if (disabled) return;
    setQuery("");
    const selectedIndex = availableOptions.findIndex((option) => selectedValues.includes(option.value) && !option.disabled);
    setActiveIndex(selectedIndex >= 0 ? selectedIndex : nextErpSelectOptionIndex(availableOptions, -1, fromEnd ? -1 : 1));
    setFocusTarget(searchPlaceholder && focusSearch ? "search" : "option");
    setOpen(true);
  }

  function toggleOption(index: number) {
    const option = visibleOptions[index];
    if (!option || option.disabled) return;
    onChange(option.value === "" ? [] : selectedValues.includes(option.value)
      ? selectedValues.filter((value) => value !== option.value)
      : [...selectedValues, option.value]);
  }

  useEffect(() => {
    if (!open) return;
    if (focusTarget === "search") searchRef.current?.focus();
    else optionRefs.current[activeIndex]?.focus();
  }, [activeIndex, focusTarget, open]);

  useEffect(() => {
    if (!open) return;
    const positionPopover = () => {
      const trigger = triggerRef.current;
      const popover = popoverRef.current;
      if (!trigger || !popover) return;
      const layout = erpSelectPopoverLayout(trigger.getBoundingClientRect(), popover.getBoundingClientRect(), {
        width: window.innerWidth, height: window.innerHeight,
      });
      popover.style.top = `${layout.top}px`;
      popover.style.left = `${layout.left}px`;
      popover.style.minWidth = `${layout.minWidth}px`;
      popover.style.maxWidth = `${layout.maxWidth}px`;
      popover.style.maxHeight = `${layout.maxHeight}px`;
    };
    positionPopover();
    window.addEventListener("resize", positionPopover);
    window.addEventListener("scroll", positionPopover, true);
    return () => {
      window.removeEventListener("resize", positionPopover);
      window.removeEventListener("scroll", positionPopover, true);
    };
  }, [open, visibleOptions.length, searchPlaceholder]);

  useOutsidePointerDown(open, rootRef, () => closeMenu());
  useEffect(() => { if (disabled) closeMenu(); }, [disabled]);

  function handleOptionKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const intent = erpSelectKeyIntent(event.key);
    if (!intent) return;
    event.preventDefault();
    event.stopPropagation();
    if (intent === "close") closeMenu(true);
    else if (intent === "select") toggleOption(index);
    else {
      setFocusTarget("option");
      setActiveIndex(nextErpSelectOptionIndex(visibleOptions,
        intent === "first" || intent === "last" ? -1 : index,
        intent === "previous" || intent === "last" ? -1 : 1));
    }
  }

  function handleBlur(event: FocusEvent<HTMLDivElement>) {
    if (!event.currentTarget.contains(event.relatedTarget as Node | null)) closeMenu();
  }

  return (
    <div ref={rootRef} onBlur={handleBlur}
      className={["erp-select erp-multi-select", open ? "erp-select--open" : "", disabled ? "erp-select--disabled" : "", className].filter(Boolean).join(" ")}
      onKeyDown={(event) => {
        if (event.key === "Escape" && open) {
          event.preventDefault();
          event.stopPropagation();
          closeMenu(true);
        }
      }}>
      <button type="button" className="erp-select__trigger" id={id} ref={triggerRef} disabled={disabled}
        title={title ?? (selectedLabel || placeholder)} aria-label={accessibleLabel} aria-labelledby={ariaLabelledBy}
        aria-haspopup="listbox" aria-expanded={open} aria-controls={listboxId}
        onClick={() => open ? closeMenu() : openMenu()}
        onKeyDown={(event) => {
          const intent = erpSelectKeyIntent(event.key);
          if (!intent || intent === "close") return;
          event.preventDefault();
          event.stopPropagation();
          if (intent === "select") open ? closeMenu() : openMenu();
          else openMenu(intent === "previous" || intent === "last", false);
        }}>
        <span className="erp-select__value">{selectedLabel || placeholder}</span>
        <span className="erp-select__arrow" aria-hidden="true" />
      </button>
      {open && <div className="erp-select__popover erp-multi-select__popover" ref={popoverRef}>
        {searchPlaceholder && <div className="erp-multi-select__search">
          <input type="text" ref={searchRef} value={query} placeholder={searchPlaceholder} aria-label={searchPlaceholder}
            autoComplete="off" spellCheck={false}
            onFocus={() => setFocusTarget("search")}
            onChange={(event) => { setQuery(event.target.value); setActiveIndex(-1); setFocusTarget("search"); }}
            onKeyDown={(event) => {
              if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                event.preventDefault();
                event.stopPropagation();
                setActiveIndex(nextErpSelectOptionIndex(visibleOptions, -1, event.key === "ArrowDown" ? 1 : -1));
                setFocusTarget("option");
              } else if (event.key === "Enter") {
                event.preventDefault();
                event.stopPropagation();
                toggleOption(nextErpSelectOptionIndex(visibleOptions, -1, 1));
              }
            }} />
        </div>}
        <div id={listboxId} role="listbox" aria-multiselectable="true" aria-label={accessibleLabel} aria-labelledby={ariaLabelledBy}
          className="erp-multi-select__options">
          {visibleOptions.map((option, index) => {
            const selected = option.value === "" ? selectedValues.length === 0 : selectedValues.includes(option.value);
            return <button type="button" className="erp-multi-select__option" key={option.value}
              role="option" aria-selected={selected} aria-disabled={option.disabled || undefined} disabled={option.disabled}
              tabIndex={index === activeIndex ? 0 : -1} ref={(element) => { optionRefs.current[index] = element; }}
              onFocus={() => { setActiveIndex(index); setFocusTarget("option"); }}
              onClick={() => toggleOption(index)} onKeyDown={(event) => handleOptionKeyDown(event, index)}>
              <span className="erp-multi-select__check" aria-hidden="true">{selected ? "✓" : ""}</span>
              <span>{option.label}</span>
            </button>;
          })}
        </div>
      </div>}
    </div>
  );
}
