import type { ComponentPropsWithRef } from "react";
import type { AppKind } from "../types";

declare const __TPV_APP_KIND__: AppKind | "test";

type Props = ComponentPropsWithRef<"button"> & { notice?: boolean };

/** Desktop dialogs use the header X; notices retain an explicit dismissal action. */
export function DialogDismissButton({ notice = false, type = "button", ...props }: Props) {
  const desktop = typeof __TPV_APP_KIND__ === "undefined" || __TPV_APP_KIND__ !== "pda";
  if (desktop && !notice) return null;
  return <button {...props} type={type} />;
}
