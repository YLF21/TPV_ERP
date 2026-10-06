import { X } from "@phosphor-icons/react";
import type { ComponentPropsWithRef } from "react";
import type { AppKind } from "../types";
import "./WindowCloseButton.css";

declare const __TPV_APP_KIND__: AppKind | "test";

type WindowCloseButtonProps = ComponentPropsWithRef<"button"> & {
  onLight?: boolean;
  desktopOnly?: boolean;
};

export function WindowCloseButton({
  className,
  children,
  onLight = false,
  desktopOnly = false,
  type = "button",
  ...props
}: WindowCloseButtonProps) {
  const desktop = typeof __TPV_APP_KIND__ === "undefined" || __TPV_APP_KIND__ !== "pda";
  if (desktopOnly && !desktop) return null;
  return (
    <button
      {...props}
      type={type}
      className={desktop
        ? ["erp-window-close", onLight && "erp-window-close-on-light", className].filter(Boolean).join(" ")
        : className}
    >
      {desktop ? <X size={22} weight="bold" aria-hidden="true" focusable="false" /> : children}
    </button>
  );
}
