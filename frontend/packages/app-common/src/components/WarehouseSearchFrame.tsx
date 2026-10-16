import type { ReactNode } from "react";
import searchIcon from "../assets/reports/search.png";

export function WarehouseSearchFrame({ children }: { children: ReactNode }) {
  return <span className="gestion-warehouse-search-frame"><img src={searchIcon} alt="" />{children}</span>;
}
