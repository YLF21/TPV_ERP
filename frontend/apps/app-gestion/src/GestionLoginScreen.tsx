import "../../../packages/app-common/src/components/DesktopLoginScreen.css";
import "./GestionLoginScreen.css";
import { type ComponentProps, useEffect, useId, useRef, useState } from "react";
import { Gear } from "@phosphor-icons/react";
import { LoginScreen } from "../../../packages/app-common/src/components/LoginScreen";
import { useOutsidePointerDown } from "../../../packages/app-common/src/components/useOutsidePointerDown";
import { createTranslator } from "../../../packages/app-common/src/i18n/LocalizedMessages";

export default function GestionLoginScreen({ secondaryActionLabel, onSecondaryAction, ...props }: ComponentProps<typeof LoginScreen>) {
  const desktop = props.presentation !== "embedded";
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const actionRef = useRef<HTMLButtonElement>(null);
  const menuId = useId();
  const t = createTranslator(props.locale);
  useOutsidePointerDown(open, rootRef, () => setOpen(false));
  useEffect(() => { if (open) actionRef.current?.focus(); }, [open]);

  function closeMenu() {
    setOpen(false);
    triggerRef.current?.focus();
  }

  return <LoginScreen {...props}
    secondaryActionLabel={desktop ? undefined : secondaryActionLabel}
    onSecondaryAction={onSecondaryAction}
    headerActions={desktop && secondaryActionLabel && onSecondaryAction ? <div ref={rootRef} className="login-settings"
      onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false); }}>
      <button ref={triggerRef} type="button" className="language-button login-settings-button"
        aria-label={t("login.settings")} title={t("login.settings")}
        aria-expanded={open} aria-haspopup="menu" aria-controls={open ? menuId : undefined}
        onClick={() => setOpen(value => !value)}
        onKeyDown={event => {
          if (event.key === "ArrowDown" || event.key === "ArrowUp") { event.preventDefault(); setOpen(true); }
          if (event.key === "Escape") { event.preventDefault(); closeMenu(); }
        }}>
        <Gear size={22} aria-hidden="true" />
      </button>
      {open && <section id={menuId} className="language-picker login-settings-menu" role="menu" aria-label={t("login.settings")}
        onKeyDown={event => {
          if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); closeMenu(); }
          if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) { event.preventDefault(); actionRef.current?.focus(); }
        }}>
        <button ref={actionRef} type="button" role="menuitem" onClick={() => { closeMenu(); onSecondaryAction(); }}>
          <span>{secondaryActionLabel}</span>
        </button>
      </section>}
    </div> : undefined} />;
}
