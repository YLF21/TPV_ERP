import type { View } from "../shared/types";

const paths: Partial<Record<View, string>> = {
  dashboard: "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z",
  reports: "M4 3v18h17M8 16v-5M13 16V7M18 16V4",
  companies: "M4 21V5l8-2v18M12 9h8v12M2 21h20M7 8h2M7 12h2M7 16h2M15 13h2M15 17h2",
  stores: "M3 10v11h18V10M2 10l2-7h16l2 7M2 10c0 4 5 4 5 0 0 4 5 4 5 0 0 4 5 4 5 0 0 4 5 4 5 0M9 21v-6h6v6",
  licenses: "M4 4h16v16H4zM8 9h8M8 13h4M15 16l2 2 3-4",
  "create-license": "M14 3H4v18h16V9M14 3v6h6M8 15h8M12 11v8",
  billing: "M5 3h14v18l-3-2-4 2-4-2-3 2V3M8 7h8M8 11h8M8 15h4",
  health: "M3 12h4l3-8 4 16 3-8h4",
  failures: "M12 3 2 21h20L12 3ZM12 9v5M12 17v.2",
  sync: "M20 7a9 9 0 0 0-15-2L2 8M2 3v5h5M4 17a9 9 0 0 0 15 2l3-3M22 21v-5h-5",
  fiscal: "M14 3H4v18h16V9M14 3v6h6M8 14l3 3 5-5",
  outbox: "M3 15h5l2 3h4l2-3h5v6H3v-6ZM12 3v10M8 9l4 4 4-4",
  support: "M4 13v-1a8 8 0 0 1 16 0v1M4 12H3v7h4v-7H4ZM20 12h1v7h-4v-7h3ZM20 19a3 3 0 0 1-3 3h-4",
  integrations: "M8 3v5M16 3v5M5 8h14M7 8v5a5 5 0 0 0 10 0V8M12 18v4",
  "fiscal-policy": "M4 6h16M4 12h16M4 18h16M8 3v6M16 9v6M10 15v6",
  users: "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM2 21v-3a7 7 0 0 1 14 0v3M17 4a4 4 0 0 1 0 7M19 15a5 5 0 0 1 3 5",
  access: "M5 10h14v11H5zM8 10V7a4 4 0 0 1 8 0v3M12 14v3",
  audit: "M8 3h8v4H8zM8 5H4v16h16V5h-4M8 11h8M8 15h6"
};

export function NavigationIcon({ view }: { view: View }) {
  return <svg className="nav-module-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
    <path d={paths[view] ?? paths.dashboard} />
  </svg>;
}
