import { ArrowLeft } from "@phosphor-icons/react";
import "./ModuleNavBackButton.css";

type ModuleNavBackButtonProps = {
  label: string;
  onBack: () => void;
  className?: string;
};

export function ModuleNavBackButton({ label, onBack, className = "" }: ModuleNavBackButtonProps) {
  const classes = `report-back module-nav-back${className ? ` ${className}` : ""}`;

  return (
    <button type="button" className={classes} onClick={onBack}>
      <ArrowLeft className="module-nav-back-icon" size={14} weight="bold" aria-hidden="true" />
      <span>{label}</span>
    </button>
  );
}
