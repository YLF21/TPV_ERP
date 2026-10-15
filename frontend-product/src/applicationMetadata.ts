import { ChartBar, CloudArrowUp, Cube, ShoppingCart } from "@phosphor-icons/react";
import type { getMarketingContent } from "./marketingI18n";

export type AppId = "venta" | "gestion" | "pda" | "saas";
export type MarketingCopy = ReturnType<typeof getMarketingContent>;

export const appMeta = [
  { id: "venta", number: "01", icon: ShoppingCart },
  { id: "gestion", number: "02", icon: ChartBar },
  { id: "pda", number: "03", icon: Cube },
  { id: "saas", number: "04", icon: CloudArrowUp }
] as const;
