import "./TouchKeyboardToggle.css";
import "./TouchKeyboardSurfaces.css";

export function TouchKeyboardToggle({ expanded, openLabel, closeLabel, controls, className = "", disabled = false, onClick }: {
  expanded: boolean;
  openLabel: string;
  closeLabel: string;
  controls?: string;
  className?: string;
  disabled?: boolean;
  onClick: () => void;
}) {
  const label = expanded ? closeLabel : openLabel;
  return <button type="button" className={`touch-keyboard-toggle ${className}`.trim()}
    aria-label={label} title={label} aria-expanded={expanded} aria-controls={controls}
    disabled={disabled} onClick={onClick}
    onKeyDown={(event) => {
      if (event.key === "Enter" || event.key === " ") event.stopPropagation();
    }}>
    <svg className="touch-keyboard-toggle-icon" viewBox="0 0 28 22" fill="none" stroke="currentColor"
      strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      <rect x="1.5" y="2.5" width="25" height="17" rx="2" />
      <path d="M6 7h1m4 0h1m4 0h1m4 0h1M6 11h1m4 0h1m4 0h1m4 0h1M8 15h12" />
    </svg>
    <svg className="touch-keyboard-toggle-arrow" viewBox="0 0 12 8" fill="currentColor" aria-hidden="true" focusable="false">
      <path d={expanded ? "M1 1h10L6 7z" : "M1 7h10L6 1z"} />
    </svg>
  </button>;
}
