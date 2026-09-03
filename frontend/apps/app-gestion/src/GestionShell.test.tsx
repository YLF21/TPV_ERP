/** @vitest-environment jsdom */
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { Gauge, ShieldCheck, Ticket } from "@phosphor-icons/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { UserSession } from "@tpverp/app-common";
import { GestionShell } from "./GestionShell";

const session: UserSession = {
  username: "ADMIN",
  displayName: "ADMIN",
  accessToken: "token",
  permissions: ["ADMIN"]
};

afterEach(() => {
  cleanup();
  window.localStorage.clear();
});

describe("GestionShell", () => {
  it("centers navigable destinations except Control fiscal without changing group headers", () => {
    render(
      <GestionShell
        session={session}
        t={(key) => key}
        activeKey="dashboard"
        navigation={[
          { key: "dashboard", label: "Resumen", icon: Gauge, onOpen: vi.fn() },
          { key: "verifactu", label: "Control fiscal", icon: ShieldCheck, lock: "FISCAL", onOpen: vi.fn() },
          {
            key: "sales",
            label: "Documentos cliente",
            icon: Ticket,
            children: [{ key: "tickets", label: "Tickets", icon: Ticket, onOpen: vi.fn() }]
          }
        ]}
      >
        <section>Contenido</section>
      </GestionShell>
    );

    const directDestination = screen.getByRole("button", { name: "Resumen" });
    const fiscalDestination = screen.getByRole("button", { name: "Control fiscal" });
    const groupHeader = screen.getByRole("button", { name: "Documentos cliente" });

    expect(directDestination).toHaveClass("gestion-nav-destination");
    expect(directDestination.querySelector("svg")).toHaveAttribute("width", "20");
    expect(fiscalDestination).not.toHaveClass("gestion-nav-destination");
    expect(fiscalDestination).toHaveClass("gestion-nav-standard");
    expect(fiscalDestination.querySelector("svg")).toHaveAttribute("width", "16");
    expect(groupHeader).not.toHaveClass("gestion-nav-destination");
    expect(groupHeader.querySelector("svg")).toHaveAttribute("width", "16");

    fireEvent.click(groupHeader);
    const childDestination = screen.getByRole("button", { name: "Tickets" });
    expect(childDestination).toHaveClass("gestion-nav-destination");
    expect(childDestination.querySelector("svg")).toHaveAttribute("width", "20");
    expect(childDestination.querySelector(".gestion-nav-destination-label")).toHaveTextContent("Tickets");

    fireEvent.change(screen.getByRole("searchbox", { name: "gestion.navigationSearch" }), {
      target: { value: "control fiscal" }
    });
    const fiscalSearchResult = screen.getByRole("button", { name: "Control fiscal" });
    expect(fiscalSearchResult).toHaveClass("gestion-nav-standard");
    expect(fiscalSearchResult).not.toHaveClass("gestion-nav-destination");
  });

  it("resizes the navigation with an accessible keyboard separator and persists the user preference", () => {
    render(
      <GestionShell
        session={session}
        t={(key) => key}
        activeKey="dashboard"
        navigation={[{ key: "dashboard", label: "Resumen", icon: Gauge, onOpen: vi.fn() }]}
      >
        <section>Contenido</section>
      </GestionShell>
    );

    const separator = screen.getByRole("separator", { name: "gestion.navigationResize" });
    const shell = separator.closest(".gestion-screen");
    expect(separator).toHaveAttribute("aria-valuenow", "238");

    fireEvent.keyDown(separator, { key: "ArrowRight" });
    expect(separator).toHaveAttribute("aria-valuenow", "254");
    expect(shell).toHaveStyle({ "--gestion-navigation-width": "254px" });
    expect(window.localStorage.getItem("tpv-erp:gestion-navigation-width:ADMIN")).toBe("254");

    fireEvent.keyDown(separator, { key: "Home" });
    expect(separator).toHaveAttribute("aria-valuenow", "210");

    fireEvent.keyDown(separator, { key: "End" });
    expect(separator).toHaveAttribute("aria-valuenow", "420");
  });

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
          { key: "stock.party.members", label: "Miembros", onOpen: openMembers },
          { key: "stock.party.suppliers", label: "Proveedores", onOpen: openSuppliers }
        ]}
      >
        <section>Contenido</section>
      </GestionShell>
    );

    expect(screen.getByRole("button", { name: "Clientes" })).not.toHaveAttribute("aria-expanded");
    expect(screen.getByRole("button", { name: "Miembros" })).not.toHaveAttribute("aria-expanded");
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
    fireEvent.click(screen.getByRole("button", { name: "Miembros" }));
    fireEvent.click(screen.getByRole("button", { name: "Proveedores" }));
    expect(openCustomers).toHaveBeenCalledOnce();
    expect(openMembers).toHaveBeenCalledOnce();
    expect(openSuppliers).toHaveBeenCalledOnce();
    expect(screen.queryByRole("button", { name: "Almacenes" })).not.toBeInTheDocument();
  });

  it("finds permitted destinations and opens them from the navigation search", () => {
    const openTickets = vi.fn();
    render(
      <GestionShell
        session={session}
        t={(key) => key}
        activeKey="dashboard"
        navigation={[
          { key: "dashboard", label: "Resumen", onOpen: vi.fn() },
          { key: "sales", label: "Ventas", children: [{ key: "tickets", label: "Tickets", onOpen: openTickets }] }
        ]}
      >
        <section>Contenido</section>
      </GestionShell>
    );

    const search = screen.getByRole("searchbox", { name: "gestion.navigationSearch" });
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    expect(search).toHaveFocus();
    fireEvent.change(search, { target: { value: "ticket" } });
    fireEvent.click(screen.getByRole("button", { name: "Ventas / Tickets" }));

    expect(openTickets).toHaveBeenCalledOnce();
    expect(search).toHaveValue("");
  });

  it("does not open a protected destination until the backend-session unlock succeeds", async () => {
    const openFiscal = vi.fn();
    let resolveUnlock: ((allowed: boolean) => void) | undefined;
    const requestOpenDestination = vi.fn(() => new Promise<boolean>((resolve) => {
      resolveUnlock = resolve;
    }));
    render(
      <GestionShell
        session={session}
        t={(key) => key}
        activeKey="dashboard"
        navigation={[
          { key: "dashboard", label: "Resumen", onOpen: vi.fn() },
          { key: "verifactu", label: "Control fiscal", lock: "FISCAL", onOpen: openFiscal },
        ]}
        requestOpenDestination={requestOpenDestination}
      >
        <section>Contenido</section>
      </GestionShell>
    );

    fireEvent.click(screen.getByRole("button", { name: "Control fiscal" }));
    expect(requestOpenDestination).toHaveBeenCalledWith(expect.objectContaining({ lock: "FISCAL" }));
    expect(openFiscal).not.toHaveBeenCalled();

    await act(async () => resolveUnlock?.(true));
    expect(openFiscal).toHaveBeenCalledOnce();
  });
});
