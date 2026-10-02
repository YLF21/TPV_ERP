import type { AppKind } from "../types";
import ventaLogo from "../../../../branding/app-venta.png";
import gestionLogo from "../../../../branding/app-gestion.png";

export function AppLogo({ app }: { app: AppKind }) {
  if (app === "pda") return null;
  return (
    <img
      className="app-logo"
      src={app === "venta" ? ventaLogo : gestionLogo}
      alt=""
      aria-hidden="true"
      draggable={false}
      width={24}
      height={24}
    />
  );
}
