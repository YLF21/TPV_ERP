import { CaretDown, User } from "@phosphor-icons/react";
import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { useOutsidePointerDown } from "./useOutsidePointerDown";
import "./LoginUsernameHistory.css";

type Props = {
  id: string;
  value: string;
  history: string[];
  placeholder: string;
  historyLabel: string;
  disabled: boolean;
  onChange: (value: string) => void;
};

export function LoginUsernameHistory({ id, value, history, placeholder, historyLabel, disabled, onChange }: Props) {
  const rootRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const query = value.trim().toLocaleLowerCase();
  const options = history.filter((user) => user.toLocaleLowerCase().includes(query));
  const expanded = open && options.length > 0 && !disabled;
  const listId = `${id}-history`;

  useOutsidePointerDown(expanded, rootRef, () => setOpen(false));
  useEffect(() => {
    if (expanded) optionRefs.current[activeIndex]?.scrollIntoView?.({ block: "nearest" });
  }, [expanded, activeIndex]);

  function choose(user: string) {
    onChange(user);
    setOpen(false);
    inputRef.current?.focus();
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if ((event.key === "ArrowDown" || event.key === "ArrowUp") && options.length > 0) {
      event.preventDefault();
      if (!expanded) {
        setOpen(true);
        setActiveIndex(event.key === "ArrowDown" ? 0 : options.length - 1);
      } else {
        setActiveIndex((index) => Math.max(0, Math.min(options.length - 1,
          index + (event.key === "ArrowDown" ? 1 : -1))));
      }
    } else if (event.key === "Enter" && expanded) {
      event.preventDefault();
      event.stopPropagation();
      if (options[activeIndex] !== undefined) choose(options[activeIndex]);
    } else if (event.key === "Escape" && expanded) {
      event.preventDefault();
      event.stopPropagation();
      setOpen(false);
    } else if (event.key === "Tab") {
      setOpen(false);
    }
  }

  return <div className="login-input-wrap login-username-history" ref={rootRef}
    onBlurCapture={(event) => {
      if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
    }}>
    <User size={32} weight="fill" aria-hidden="true" />
    <input id={id} ref={inputRef} autoFocus autoComplete="username" role="combobox"
      aria-autocomplete="list" aria-expanded={expanded} aria-controls={expanded ? listId : undefined}
      aria-activedescendant={expanded ? `${listId}-${activeIndex}` : undefined}
      value={value} disabled={disabled} placeholder={placeholder} onKeyDown={handleKeyDown}
      onChange={(event) => { onChange(event.currentTarget.value); setActiveIndex(0); setOpen(true); }} />
    {history.length > 0 && <button type="button" className="login-username-history-toggle"
      aria-label={historyLabel} aria-haspopup="listbox" aria-expanded={expanded} aria-controls={listId}
      disabled={disabled || options.length === 0} tabIndex={-1}
      onMouseDown={(event) => event.preventDefault()}
      onClick={() => { setOpen(!expanded); setActiveIndex(0); inputRef.current?.focus(); }}>
      <CaretDown size={24} weight="fill" aria-hidden="true" />
    </button>}
    {expanded && <div id={listId} className="login-username-history-list" role="listbox" aria-label={historyLabel}>
      {options.map((user, index) => <button key={user} id={`${listId}-${index}`} type="button" role="option"
        ref={(node) => { optionRefs.current[index] = node; }} tabIndex={-1}
        aria-selected={index === activeIndex} onMouseEnter={() => setActiveIndex(index)}
        onMouseDown={(event) => event.preventDefault()} onClick={() => choose(user)}>{user}</button>)}
    </div>}
  </div>;
}
