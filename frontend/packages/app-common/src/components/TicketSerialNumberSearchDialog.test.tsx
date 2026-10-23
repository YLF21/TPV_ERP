// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { TicketSerialNumberSearchDialog } from "./TicketSerialNumberSearchDialog";
import { createTranslator } from "../i18n/LocalizedMessages";
const match = { ticketId: "ticket-1", lineId: "line-1", number: "T-001", date: "2026-10-17",
  status: "PAGADO", total: "8.20", productCode: "P-1", productName: "Cargador", serialNumber: "123" };
const second = { ...match, ticketId: "ticket-2", lineId: "line-2", number: "T-002", productName: "Otro producto" };
function bottom(element: Element) {
  Object.defineProperties(element, { scrollHeight: { configurable: true, value: 1000 },
    clientHeight: { configurable: true, value: 400 }, scrollTop: { configurable: true, writable: true, value: 600 } });
  fireEvent.scroll(element);
}
afterEach(() => { cleanup(); localStorage.clear(); });
describe("Ticket serial search", () => {
  it("prepares the first result and moves to the next ticket without opening it", async () => {
    const open = vi.fn();
    const request = vi.fn().mockResolvedValue({ items: [match, second], nextPage: null });
    const { container } = render(<TicketSerialNumberSearchDialog locale="es" token="token" request={request}
      onClose={vi.fn()} onOpenTicket={open} />);
    await screen.findByText("T-002");
    const rows = [...container.querySelectorAll<HTMLTableRowElement>(".serial-search-results tbody tr")];
    await waitFor(() => expect(rows[0]).toHaveFocus());
    expect(rows[0]).toHaveClass("selected");
    fireEvent.keyDown(rows[0], { key: "ArrowDown" });
    expect(rows[1]).toHaveClass("selected");
    expect(rows[1]).toHaveFocus();
    fireEvent.keyUp(rows[1], { key: "ArrowDown" });
    fireEvent.keyDown(rows[1], { key: "ArrowDown" });
    expect(rows[1]).toHaveClass("selected");
    expect(open).not.toHaveBeenCalled();
  });

  it("loads on opening, filters while typing and restores all rows when cleared", async () => {
    const request = vi.fn().mockResolvedValue({ items: [match], nextPage: null });
    const open = vi.fn();
    render(<TicketSerialNumberSearchDialog locale="es" token="token" request={request} onClose={vi.fn()} onOpenTicket={open} />);
    await screen.findByText("T-001");
    expect(request).toHaveBeenCalledWith("/tickets/serial-number-search?serialNumber=&page=0", { token: "token" });
    fireEvent.change(screen.getByRole("textbox"), { target: { value: " 23 " } });
    await waitFor(() => expect(request).toHaveBeenLastCalledWith("/tickets/serial-number-search?serialNumber=23&page=0", { token: "token" }));
    await screen.findByText("T-001");
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "" } });
    await waitFor(() => expect(request).toHaveBeenLastCalledWith("/tickets/serial-number-search?serialNumber=&page=0", { token: "token" }));
    fireEvent.click(await screen.findByRole("button", { name: "Abrir documento" }));
    expect(open).toHaveBeenCalledWith(match);
  });
  it("keeps the same table header while loading and after a filter has no matches", async () => {
    const request = vi.fn().mockResolvedValueOnce({ items: [match], nextPage: null })
      .mockResolvedValueOnce({ items: [], nextPage: null });
    const { container } = render(<TicketSerialNumberSearchDialog locale="es" token="token" request={request}
      onClose={vi.fn()} onOpenTicket={vi.fn()} />);
    const header = container.querySelector("thead");
    expect(header).toBeInTheDocument();
    await screen.findByText("T-001");
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "missing" } });
    expect(container.querySelector("thead")).toBe(header);
    await screen.findByText("SIN DATOS");
    expect(container.querySelector("thead")).toBe(header);
    expect(container.querySelectorAll("thead th")).toHaveLength(8);
    expect(container.querySelectorAll("tbody tr")).toHaveLength(0);
  });
  it("appends rows on internal scroll and prevents duplicate page requests", async () => {
    const request = vi.fn().mockResolvedValueOnce({ items: [match], nextPage: 1 })
      .mockResolvedValueOnce({ items: [match, second], nextPage: null });
    const { container } = render(<TicketSerialNumberSearchDialog locale="es" token="token" request={request} onClose={vi.fn()} onOpenTicket={vi.fn()} />);
    await screen.findByText("T-001");
    const scroll = container.querySelector(".serial-search-results")!;
    bottom(scroll); fireEvent.scroll(scroll);
    expect(await screen.findByText("T-002")).toBeInTheDocument();
    expect(screen.getAllByText("T-001")).toHaveLength(1);
    expect(request).toHaveBeenCalledTimes(2);
    expect(request).toHaveBeenLastCalledWith("/tickets/serial-number-search?serialNumber=&page=1", { token: "token" });
    expect(screen.queryByRole("button", { name: "Siguiente" })).not.toBeInTheDocument();
  });
  it("preserves ERP resizing and sorting when more rows load", async () => {
    const request = vi.fn().mockResolvedValue({ items: [match], nextPage: 1 });
    const { container } = render(<TicketSerialNumberSearchDialog locale="es" token="token" request={request} onClose={vi.fn()} onOpenTicket={vi.fn()} />);
    await screen.findByText("T-001");
    const header = container.querySelector('th[data-column-key="number"]')!;
    fireEvent.keyDown(header.querySelector(".table-layout-column-resizer")!, { key: "ArrowRight" });
    expect(container.querySelector("colgroup col")).toHaveStyle({ width: "172px" });
    fireEvent.click(header.querySelector(".table-layout-column-menu-button")!);
    fireEvent.click(screen.getByRole("menuitem", { name: /Cambiar orden/ }));
    await waitFor(() => expect(request).toHaveBeenLastCalledWith(
      "/tickets/serial-number-search?serialNumber=&page=0&sortBy=number&sortDirection=asc", { token: "token" }));
    await screen.findByText("T-001");
    bottom(container.querySelector(".serial-search-results")!);
    await waitFor(() => expect(request).toHaveBeenLastCalledWith(
      "/tickets/serial-number-search?serialNumber=&page=1&sortBy=number&sortDirection=asc", { token: "token" }));
  });
  it("keeps typing enabled and discards a late response for an older filter", async () => {
    let resolveOld!: (value: unknown) => void;
    const request = vi.fn().mockImplementation((path: string) => path.includes("serialNumber=old")
      ? new Promise(resolve => { resolveOld = resolve; })
      : Promise.resolve({ items: path.includes("serialNumber=new") ? [second] : [match], nextPage: null }));
    render(<TicketSerialNumberSearchDialog locale="es" token="token" request={request} onClose={vi.fn()} onOpenTicket={vi.fn()} />);
    await screen.findByText("T-001");
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "old" } });
    await waitFor(() => expect(resolveOld).toBeDefined());
    expect(screen.getByRole("textbox")).toBeEnabled();
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "new" } });
    await screen.findByText("T-002");
    await act(async () => resolveOld({ items: [match], nextPage: null }));
    expect(screen.queryByText("T-001")).not.toBeInTheDocument();
    expect(screen.getByText("T-002")).toBeInTheDocument();
  });
  it.each(["button", "menu"] as const)("closes only the column menu with Escape from its %s and preserves the search", async target => {
    const request = vi.fn().mockResolvedValue({ items: [match], nextPage: null });
    const close = vi.fn();
    const { container } = render(<TicketSerialNumberSearchDialog locale="es" token="token" request={request}
      onClose={close} onOpenTicket={vi.fn()} />);
    await screen.findByText("T-001");
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "23" } });
    await waitFor(() => expect(request).toHaveBeenLastCalledWith(
      "/tickets/serial-number-search?serialNumber=23&page=0", { token: "token" }));
    await screen.findByText("T-001");
    const menuButton = container.querySelector<HTMLButtonElement>('th[data-column-key="number"] .table-layout-column-menu-button')!;
    menuButton.focus();
    fireEvent.click(menuButton);
    const menuItem = screen.getByRole("menuitem", { name: /Cambiar orden/ });
    if (target === "menu") menuItem.focus();
    fireEvent.keyDown(target === "menu" ? menuItem : menuButton, { key: "Escape" });
    expect(close).not.toHaveBeenCalled();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(menuButton).toHaveFocus();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("textbox")).toHaveValue("23");
    expect(screen.getByText("T-001")).toBeInTheDocument();
    expect(request).toHaveBeenCalledTimes(2);
    fireEvent.keyDown(menuButton, { key: "Escape" });
    expect(close).toHaveBeenCalledOnce();
  });
  it.each(["es", "en", "zh"] as const)("shows translated errors and closes with Escape in %s", async locale => {
    const t = createTranslator(locale); const close = vi.fn();
    render(<TicketSerialNumberSearchDialog locale={locale} token="token" request={vi.fn().mockRejectedValue(new Error("offline"))} onClose={close} onOpenTicket={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(t("salesReport.serialSearch.error")));
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    expect(close).toHaveBeenCalledOnce();
  });
});
