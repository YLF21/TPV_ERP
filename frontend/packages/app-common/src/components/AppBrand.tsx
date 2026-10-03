import type { AppKind } from "../types";
import ventaWordmark from "../../../../branding/espos-wordmark.png";
import { AppLogo } from "./AppLogo";

export function AppBrand({ app, label }: { app: AppKind; label: string }) {
  return app === "venta" ? (
    <img
      className="app-brand-wordmark"
      src={ventaWordmark}
      alt={label}
      draggable={false}
      width={94}
      height={30}
    />
  ) : <><AppLogo app={app} />{label}</>;
}
