import { ArrowLeft } from "@phosphor-icons/react";
import "./ModuleNavBackButton.css";

type ModuleNavBackButtonProps = {
  label: string;
  onBack: () => void;
  className?: string;
  disabled?: boolean;
};

export function ModuleNavBackButton({ label, onBack, className = "", disabled = false }: ModuleNavBackButtonProps) {
  const classes = `report-back module-nav-back${className ? ` ${className}` : ""}`;

  return (
    <button type="button" className={classes} disabled={disabled} onClick={onBack}>
      <ArrowLeft className="module-nav-back-icon" size={20} weight="bold" aria-hidden="true" focusable="false" />
      <span className="module-nav-back-label">{label}</span>
    </button>
  );
}
