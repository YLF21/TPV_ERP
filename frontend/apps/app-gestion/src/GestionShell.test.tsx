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

  it("keeps warehouse operations in their own group and party directories as direct accesses", () => {
    const openCustomers = vi.fn();
    const openMembers = vi.fn();
    const openSuppliers = vi.fn();
    render(
      <GestionShell
        session={session}
        t={(key) => key}
        activeKey="dashboard"
        navigation={[
          { key: "dashboard", label: "Resumen", onOpen: vi.fn() },
          {
            key: "stock",
            label: "Stock",
            children: [{ key: "stock.current", label: "Stock actual", onOpen: vi.fn() }]
          },
          {
            key: "warehouse",
            label: "Almacén",
            children: [
              { key: "stock.warehouse.management", label: "Almacenes", onOpen: vi.fn() },
              { key: "stock.warehouse.input", label: "Entrada almacén", onOpen: vi.fn() },
              { key: "stock.warehouse.output", label: "Salida almacén", onOpen: vi.fn() },
              { key: "stock.warehouse.goodsCheck", label: "Comprobación de pedido", onOpen: vi.fn() }
            ]
          },
          { key: "stock.party.customers", label: "Clientes", onOpen: openCustomers },
          { key: "stock.party.members", label: "Socios", onOpen: openMembers },
          { key: "stock.party.suppliers", label: "Proveedores", onOpen: openSuppliers }
        ]}
      >
        <section>Contenido</section>
      </GestionShell>
    );

    expect(screen.getByRole("button", { name: "Clientes" })).not.toHaveAttribute("aria-expanded");
    expect(screen.getByRole("button", { name: "Socios" })).not.toHaveAttribute("aria-expanded");
    expect(screen.getByRole("button", { name: "Proveedores" })).not.toHaveAttribute("aria-expanded");

    fireEvent.click(screen.getByRole("button", { name: "Stock" }));
    expect(screen.getByRole("button", { name: "Stock actual" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Almacenes" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Almacén" }));
    expect(screen.queryByRole("button", { name: "Stock actual" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Almacenes" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Entrada almacén" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Salida almacén" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Comprobación de pedido" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Clientes" }));
    fireEvent.click(screen.getByRole("button", { name: "Socios" }));
    fireEvent.click(screen.getByRole("button", { name: "Proveedores" }));
    expect(openCustomers).toHaveBeenCalledOnce();
    expect(openMembers).toHaveBeenCalledOnce();
    expect(openSuppliers).toHaveBeenCalledOnce();
    expect(screen.queryByRole("button", { name: "Almacenes" })).not.toBeInTheDocument();
  });
});
