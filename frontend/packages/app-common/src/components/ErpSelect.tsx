import { useEffect, useId, useRef, useState } from "react";
import type { FocusEvent, KeyboardEvent } from "react";
import "./ErpSelect.css";

export type ErpSelectOption = Readonly<{
  value: string;
  label: string;
  disabled?: boolean;
}>;

export type ErpSelectKeyIntent = "next" | "previous" | "select" | "close" | "first" | "last" | null;

type ErpSelectProps = {
  value: string;
  options: readonly ErpSelectOption[];
  onChange: (value: string) => void;
  onCommit?: () => void;
  onNavigatePrevious?: () => void;
  disabled?: boolean;
  id?: string;
  className?: string;
  placeholder?: string;
  title?: string;
  "aria-label"?: string;
  "aria-labelledby"?: string;
};

export function erpSelectKeyIntent(key: string): ErpSelectKeyIntent {
  if (key === "ArrowDown") return "next";
  if (key === "ArrowUp") return "previous";
  if (key === "Enter" || key === " ") return "select";
  if (key === "Escape") return "close";
  if (key === "Home") return "first";
  if (key === "End") return "last";
  return null;
}

export function nextErpSelectOptionIndex(
  options: readonly ErpSelectOption[],
  currentIndex: number,
  direction: 1 | -1
) {
  if (options.length === 0) return -1;
  let index = currentIndex >= 0 && currentIndex < options.length
    ? currentIndex
    : direction === 1 ? -1 : 0;
  for (let offset = 0; offset < options.length; offset += 1) {
    index = (index + direction + options.length) % options.length;
    if (!options[index]?.disabled) return index;
  }
  return -1;
}

function edgeErpSelectOptionIndex(options: readonly ErpSelectOption[], fromEnd: boolean) {
  const start = fromEnd ? options.length - 1 : 0;
  const end = fromEnd ? -1 : options.length;
  const step = fromEnd ? -1 : 1;
  for (let index = start; index !== end; index += step) {
    if (!options[index]?.disabled) return index;
  }
  return -1;
}

export function ErpSelect({
  value,
  options,
  onChange,
  onCommit,
  onNavigatePrevious,
  disabled = false,
  id,
  className,
  placeholder = "-",
  title,
  "aria-label": ariaLabel,
  "aria-labelledby": ariaLabelledBy
}: ErpSelectProps) {
  const generatedId = useId();
  const listboxId = `${id ?? generatedId}-listbox`;
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const selectedIndex = options.findIndex((option) => option.value === value);
  const selectedOption = selectedIndex >= 0 ? options[selectedIndex] : undefined;
  const accessibleLabel = ariaLabel ?? selectedOption?.label ?? placeholder;

  function initialActiveIndex(fromEnd = false) {
    if (selectedIndex >= 0 && !options[selectedIndex]?.disabled) return selectedIndex;
    return edgeErpSelectOptionIndex(options, fromEnd);
  }

  function openMenu(fromEnd = false) {
    if (disabled) return;
    const nextIndex = initialActiveIndex(fromEnd);
    if (nextIndex < 0) return;
    setActiveIndex(nextIndex);
    setOpen(true);
  }

  function closeMenu(restoreFocus = false) {
    setOpen(false);
    if (restoreFocus) triggerRef.current?.focus();
  }

  function selectOption(index: number) {
    const option = options[index];
    if (!option || option.disabled) return;
    if (option.value !== value) onChange(option.value);
    closeMenu(true);
    onCommit?.();
  }

  useEffect(() => {
    if (!open) return;
    optionRefs.current[activeIndex]?.focus();
  }, [activeIndex, open]);

  useEffect(() => {
    if (!open) return;
    function handlePointerDown(event: PointerEvent) {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("pointerdown", handlePointerDown);
    return () => document.removeEventListener("pointerdown", handlePointerDown);
  }, [open]);

  useEffect(() => {
    if (disabled) setOpen(false);
  }, [disabled]);

  function handleTriggerKeyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (event.key === "Enter" && event.shiftKey && onNavigatePrevious) {
      event.preventDefault();
      closeMenu();
      onNavigatePrevious();
      return;
    }
    const intent = erpSelectKeyIntent(event.key);
    if (intent === "close") {
      if (open) {
        event.preventDefault();
        closeMenu();
      }
      return;
    }
    if (intent === "next" || intent === "previous") {
      event.preventDefault();
      if (!open) {
        openMenu(intent === "previous");
      } else {
        setActiveIndex((current) => nextErpSelectOptionIndex(options, current, intent === "next" ? 1 : -1));
      }
      return;
    }
    if (intent === "select" && !open) {
      event.preventDefault();
      openMenu();
    }
  }

  function handleOptionKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    if (event.key === "Enter" && event.shiftKey && onNavigatePrevious) {
      event.preventDefault();
      closeMenu(true);
      onNavigatePrevious();
      return;
    }
    const intent = erpSelectKeyIntent(event.key);
    if (!intent) return;
    if (intent === "close") {
      event.preventDefault();
      closeMenu(true);
      return;
    }
    if (intent === "select") {
      event.preventDefault();
      selectOption(index);
      return;
    }
    event.preventDefault();
    if (intent === "first" || intent === "last") {
      setActiveIndex(edgeErpSelectOptionIndex(options, intent === "last"));
      return;
    }
    setActiveIndex((current) => nextErpSelectOptionIndex(options, current, intent === "next" ? 1 : -1));
  }

  function handleBlur(event: FocusEvent<HTMLDivElement>) {
    if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOpen(false);
  }

  const rootClassName = ["erp-select", open ? "erp-select--open" : "", disabled ? "erp-select--disabled" : "", className]
    .filter(Boolean)
    .join(" ");

  return (
    <div className={rootClassName} ref={rootRef} onBlur={handleBlur}>
      <button
        type="button"
        className="erp-select__trigger"
        id={id}
        ref={triggerRef}
        disabled={disabled}
        title={title}
        aria-label={accessibleLabel}
        aria-labelledby={ariaLabelledBy}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={listboxId}
        onClick={() => open ? closeMenu() : openMenu()}
        onKeyDown={handleTriggerKeyDown}
      >
        <span className="erp-select__value">{selectedOption?.label ?? placeholder}</span>
        <span className="erp-select__arrow" aria-hidden="true" />
      </button>
      {open && (
        <div
          className="erp-select__popover"
          id={listboxId}
          role="listbox"
          aria-label={accessibleLabel}
          aria-labelledby={ariaLabelledBy}
        >
          {options.map((option, index) => (
            <button
              type="button"
              className="erp-select__option"
              id={`${listboxId}-option-${index}`}
              role="option"
              aria-selected={option.value === value}
              aria-disabled={option.disabled || undefined}
              data-active={index === activeIndex ? "true" : undefined}
              disabled={option.disabled}
              tabIndex={index === activeIndex ? 0 : -1}
              key={option.value}
              ref={(element) => { optionRefs.current[index] = element; }}
              onClick={() => selectOption(index)}
              onKeyDown={(event) => handleOptionKeyDown(event, index)}
              onPointerMove={() => {
                if (!option.disabled) setActiveIndex(index);
              }}
            >
              {option.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
