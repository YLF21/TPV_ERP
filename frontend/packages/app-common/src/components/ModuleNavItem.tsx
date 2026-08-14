import type { ButtonHTMLAttributes, ReactNode } from "react";
import "./ModuleNavItem.css";

type ModuleNavItemProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, "children"> & {
  icon: ReactNode;
  label: string;
  selected?: boolean;
};

export function ModuleNavItem({
  icon,
  label,
  selected = false,
  className = "",
  ...buttonProps
}: ModuleNavItemProps) {
  const classes = `module-nav-item${selected ? " selected" : ""}${className ? ` ${className}` : ""}`;

  return (
    <button
      {...buttonProps}
      type="button"
      className={classes}
      aria-current={selected ? "page" : buttonProps["aria-current"]}
    >
      <span className="module-nav-item-icon" aria-hidden="true">{icon}</span>
      <span className="module-nav-item-label">{label}</span>
    </button>
  );
}
