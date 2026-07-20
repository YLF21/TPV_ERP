/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { UserSession } from "@tpverp/app-common";
import { GestionShell } from "./GestionShell";

const session: UserSession = {
  username: "ADMIN",
  displayName: "ADMIN",
  accessToken: "token",
  permissions: ["ADMIN"]
};

afterEach(cleanup);

describe("GestionShell", () => {
  it("keeps one sidebar and expands grouped module options", () => {
    const openTickets = vi.fn();
    render(
      <GestionShell
        session={session}
        t={(key) => key}
        activeKey="dashboard"
        navigation={[
          { key: "dashboard", label: "Resumen", onOpen: vi.fn() },
          {
            key: "sales",
            label: "Ventas",
            children: [{ key: "tickets", label: "Tickets", onOpen: openTickets }]
          }
        ]}
      >
        <section>Contenido</section>
      </GestionShell>
    );

    expect(screen.queryByRole("button", { name: "Tickets" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Ventas" }));
    fireEvent.click(screen.getByRole("button", { name: "Tickets" }));
    expect(openTickets).toHaveBeenCalledOnce();
    expect(document.querySelectorAll(".gestion-nav")).toHaveLength(1);
  });

  it("automatically expands the group containing the active child", () => {
    render(
      <GestionShell
        session={session}
        t={(key) => key}
        activeKey="stock.current"
        navigation={[{
          key: "stock",
          label: "Stock",
          children: [{ key: "stock.current", label: "Inventario", onOpen: vi.fn() }]
        }]}
      >
        <section>Contenido</section>
      </GestionShell>
    );

    expect(screen.getByRole("button", { name: "Inventario" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("button", { name: "Stock" })).toHaveAttribute("aria-expanded", "true");
  });

  it("keeps only one grouped module expanded", () => {
    render(
      <GestionShell
        session={session}
        t={(key) => key}
        activeKey="dashboard"
        navigation={[
          { key: "dashboard", label: "Resumen", onOpen: vi.fn() },
          {
            key: "sales",
            label: "Ventas",
            children: [{ key: "tickets", label: "Tickets", onOpen: vi.fn() }]
          },
          {
            key: "stock",
            label: "Stock",
            children: [{ key: "stock.current", label: "Inventario", onOpen: vi.fn() }]
          }
        ]}
      >
        <section>Contenido</section>
      </GestionShell>
    );

    fireEvent.click(screen.getByRole("button", { name: "Ventas" }));
    expect(screen.getByRole("button", { name: "Tickets" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Stock" }));
    expect(screen.queryByRole("button", { name: "Tickets" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Inventario" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ventas" })).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByRole("button", { name: "Stock" })).toHaveAttribute("aria-expanded", "true");
  });
});
