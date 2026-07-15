import { useRef, useState } from "react";
import type { LocaleCode, UserSession } from "../types";
import languageIcon from "../assets/language.png";
import { TopDateTime } from "./TopDateTime";
import { useOutsidePointerDown } from "./useOutsidePointerDown";

type SessionTopControlsProps = {
  locale: LocaleCode;
  session: UserSession;
  languageLabel: string;
  shutdownLabel: string;
  changePasswordLabel: string;
  logoutLabel: string;
  shutdownConfirmTitle: string;
  shutdownConfirmText: string;
  noLabel: string;
  yesLabel: string;
  onLocaleChange: (locale: LocaleCode) => void;
  onLogout?: () => void;
  onPrepareShutdown?: () => Promise<boolean>;
  onBrowserClose?: () => void | Promise<void>;
};

const languageOptions: Array<{ code: LocaleCode; label: string }> = [
  { code: "es", label: "Español" },
  { code: "en", label: "English" },
  { code: "zh", label: "中文" }
];

export function SessionTopControls({
  locale,
  session,
  languageLabel,
  shutdownLabel,
  changePasswordLabel,
  logoutLabel,
  shutdownConfirmTitle,
  shutdownConfirmText,
  noLabel,
  yesLabel,
  onLocaleChange,
  onLogout,
  onPrepareShutdown,
  onBrowserClose
}: SessionTopControlsProps) {
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [languageOpen, setLanguageOpen] = useState(false);
  const [shutdownOpen, setShutdownOpen] = useState(false);
  const [shutdownPreparing, setShutdownPreparing] = useState(false);
  const shutdownPreparingRef = useRef(false);
  const userMenuRef = useRef<HTMLDivElement | null>(null);
  const languagePickerRef = useRef<HTMLDivElement | null>(null);

  useOutsidePointerDown(userMenuOpen, userMenuRef, () => setUserMenuOpen(false));
  useOutsidePointerDown(languageOpen, languagePickerRef, () => setLanguageOpen(false));

  async function closeApplication() {
    if (window.tpvDesktop) {
      await window.tpvDesktop.closeApplication();
      return;
    }
    await onBrowserClose?.();
  }

  async function handleApplicationClose() {
    if (shutdownPreparingRef.current) return;
    shutdownPreparingRef.current = true;
    setShutdownPreparing(true);
    let ready = false;
    try {
      ready = await (onPrepareShutdown?.() ?? Promise.resolve(true));
      if (ready) await closeApplication();
    } catch {
      ready = false;
    } finally {
      if (!ready) {
        setShutdownOpen(false);
        shutdownPreparingRef.current = false;
        setShutdownPreparing(false);
      }
    }
  }

  return (
    <>
      <TopDateTime locale={locale} />
      <div ref={userMenuRef} style={{ display: "contents" }}>
        <button
          type="button"
          className="report-user-button"
          aria-expanded={userMenuOpen}
          aria-haspopup="menu"
          aria-label={session.displayName}
          title={session.displayName}
          onClick={() => {
            setLanguageOpen(false);
            setUserMenuOpen((open) => !open);
          }}
        >
          {session.displayName}
        </button>
        {userMenuOpen && (
          <section className="report-user-menu" role="menu" aria-label={session.displayName}>
            <button type="button" role="menuitem" onClick={() => setUserMenuOpen(false)}>
              {changePasswordLabel}
            </button>
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                setUserMenuOpen(false);
                onLogout?.();
              }}
            >
              {logoutLabel}
            </button>
          </section>
        )}
      </div>
      <div ref={languagePickerRef} style={{ display: "contents" }}>
        <button
          type="button"
          className="language-button"
          aria-expanded={languageOpen}
          aria-haspopup="listbox"
          aria-label={languageLabel}
          title={languageLabel}
          onClick={() => {
            setUserMenuOpen(false);
            setLanguageOpen((open) => !open);
          }}
        >
          <img alt="" src={languageIcon} />
        </button>
        {languageOpen && (
          <section className="language-picker" aria-label={languageLabel}>
            {languageOptions.map((option) => (
              <button
                type="button"
                className={option.code === locale ? "selected" : ""}
                key={option.code}
                onClick={() => {
                  onLocaleChange(option.code);
                  setLanguageOpen(false);
                }}
              >
                <span>{option.label}</span>
                <strong>{option.code.toUpperCase()}</strong>
              </button>
            ))}
          </section>
        )}
      </div>
      <button
        type="button"
        className="shutdown-button"
        aria-label={shutdownLabel}
        title={shutdownLabel}
        onClick={() => setShutdownOpen(true)}
      >
        {"\u23FB"}
      </button>
      {shutdownOpen && (
        <div className="shutdown-overlay" role="dialog" aria-modal="true" aria-labelledby="shutdown-title">
          <section className="shutdown-dialog">
            <h2 id="shutdown-title">{shutdownConfirmTitle}</h2>
            <p>{shutdownConfirmText}</p>
            <div className="shutdown-actions">
              <button type="button" className="shutdown-no" autoFocus disabled={shutdownPreparing} onClick={() => setShutdownOpen(false)}>
                {noLabel}
              </button>
              <button type="button" className="shutdown-yes" disabled={shutdownPreparing} onClick={() => void handleApplicationClose()}>
                {yesLabel}
              </button>
            </div>
          </section>
        </div>
      )}
    </>
  );
}
