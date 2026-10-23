import type { ReactNode } from "react";
import searchIcon from "../assets/reports/search.png";

/** Shared search chrome; the input keeps its own accessible name and handlers. */
export function GestionSearchFrame({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <span className={`gestion-search-frame ${className}`.trim()}><img src={searchIcon} alt="" />{children}</span>;
}
