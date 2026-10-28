// @vitest-environment jsdom
import { useState } from "react";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { PartyAddressAutocomplete, type SuggestedPartyAddress } from "./PartyAddressAutocomplete";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));
const request = vi.mocked(apiRequest);
const suggestion = { id: "street/1", type: "portal", label: "Calle Mayor, 10, Madrid" };
const address: SuggestedPartyAddress = { address: "Calle Mayor, 10", postalCode: "28013", city: "Madrid", province: "Madrid", country: "ES" };
const session = { username: "admin", displayName: "Admin", permissions: [], accessToken: "token" };
const context = { postalCode: "28013", city: "Madrid", country: "ES" };
function Host({ country = "ES", postalCode = context.postalCode, city = context.city, scopeKey = "customer-1", disabled = false, select = vi.fn(), submit = vi.fn() }) {
  const [value, setValue] = useState("Existing address");
  return <form onSubmit={event => { event.preventDefault(); submit(value); }}>
    <PartyAddressAutocomplete value={value} onChange={setValue} onSelect={result => { setValue(result.address); select(result); }}
      context={{ postalCode, city, country }} session={session} disabled={disabled} invalid={false} scopeKey={scopeKey} label="Address" t={key => key} />
    <button type="submit">Save</button>
  </form>;
}
async function search(text = "Mayor") {
  fireEvent.change(screen.getByRole("combobox"), { target: { value: text } });
  await act(async () => { vi.advanceTimersByTime(300); });
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}
describe("PartyAddressAutocomplete", () => {
  beforeEach(() => { vi.useFakeTimers(); request.mockReset(); });
  afterEach(() => { cleanup(); vi.useRealTimers(); });

  it("does not search existing text on mount/focus or foreign/short input", async () => {
    const view = render(<Host />);
    fireEvent.focus(screen.getByRole("combobox"));
    await act(async () => { vi.advanceTimersByTime(1000); });
    expect(request).not.toHaveBeenCalled();
    await search("Ma");
    expect(request).not.toHaveBeenCalled();
    view.rerender(<Host country="FR" />);
    await search();
    expect(request).not.toHaveBeenCalled();
  });

  it("preserves and saves manually typed text when offline", async () => {
    request.mockRejectedValue(new Error("offline"));
    const submit = vi.fn();
    render(<Host submit={submit} />);
    await search("Manual street 23");
    expect(screen.getByRole("status").textContent).toBe("party.addressSearch.unavailable");
    expect((screen.getByRole("combobox") as HTMLInputElement).value).toBe("Manual street 23");
    fireEvent.click(screen.getByText("Save"));
    expect(submit).toHaveBeenCalledWith("Manual street 23");
  });

  it("searches the literal query without adding the current postcode or city", async () => {
    request.mockResolvedValue({ available: true, items: [] });
    render(<Host postalCode="35129" city="Mogán" />);
    await search("república");
    expect(request.mock.calls[0][0]).toBe("/party-addresses/suggestions?q=rep%C3%BAblica");
  });

  it.each(["mouse", "keyboard"])("resolves before applying a %s selection and prevents Enter submission", async mode => {
    const resolved = deferred<{ available: boolean; address: SuggestedPartyAddress }>();
    request.mockResolvedValueOnce({ available: true, items: [suggestion] }).mockReturnValueOnce(resolved.promise);
    const select = vi.fn(); const submit = vi.fn();
    render(<Host select={select} submit={submit} />);
    await search();
    const input = screen.getByRole("combobox");
    expect(screen.getByRole("link").textContent).toBe("CartoCiudad · CC BY 4.0");
    if (mode === "mouse") fireEvent.click(screen.getByRole("option"));
    else { fireEvent.keyDown(input, { key: "ArrowDown" }); fireEvent.keyDown(input, { key: "Enter" }); }
    expect(select).not.toHaveBeenCalled();
    expect(submit).not.toHaveBeenCalled();
    expect((input as HTMLInputElement).value).toBe("Mayor");
    await act(async () => { resolved.resolve({ available: true, address }); });
    expect(select).toHaveBeenCalledWith(address);
    expect((input as HTMLInputElement).value).toBe(address.address);
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(request.mock.calls[0][1]).toEqual(expect.objectContaining({ token: "token", signal: expect.any(AbortSignal) }));
    expect(request.mock.calls[1][0]).toBe("/party-addresses/resolve?id=street%2F1&type=portal");
  });

  it("aborts previous searches and ignores late out of order results", async () => {
    const first = deferred<unknown>();
    request.mockReturnValueOnce(first.promise).mockResolvedValueOnce({ available: true, items: [{ ...suggestion, label: "New result" }] });
    render(<Host />);
    await search("Old street");
    const signal = request.mock.calls[0][1]?.signal;
    await search("New street");
    expect(signal?.aborted).toBe(true);
    await act(async () => { first.resolve({ available: true, items: [suggestion] }); });
    expect(screen.getByRole("option").textContent).toBe("New result");
  });

  it.each(["edit", "scope", "country", "disabled", "unmount"])("ignores pending resolutions after %s", async change => {
    const pending = deferred<unknown>();
    request.mockResolvedValueOnce({ available: true, items: [suggestion] }).mockReturnValueOnce(pending.promise);
    const select = vi.fn();
    const view = render(<Host select={select} />);
    await search();
    fireEvent.click(screen.getByRole("option"));
    const signal = request.mock.calls[1][1]?.signal;
    if (change === "edit") fireEvent.change(screen.getByRole("combobox"), { target: { value: "Manual replacement" } });
    if (change === "scope") view.rerender(<Host select={select} scopeKey="other" />);
    if (change === "country") view.rerender(<Host select={select} country="FR" />);
    if (change === "disabled") view.rerender(<Host select={select} disabled />);
    if (change === "unmount") view.unmount();
    expect(signal?.aborted).toBe(true);
    await act(async () => { pending.resolve({ available: true, address }); });
    expect(select).not.toHaveBeenCalled();
  });

  it("times out while allowing manual save and ignores a later response", async () => {
    const pending = deferred<unknown>();
    request.mockReturnValue(pending.promise);
    const submit = vi.fn();
    render(<Host submit={submit} />);
    await search();
    await act(async () => { vi.advanceTimersByTime(5000); });
    expect(request.mock.calls[0][1]?.signal?.aborted).toBe(true);
    expect(screen.getByRole("status").textContent).toBe("party.addressSearch.unavailable");
    await act(async () => { pending.resolve({ available: true, items: [suggestion] }); });
    expect(screen.queryByRole("option")).toBeNull();
    fireEvent.click(screen.getByText("Save"));
    expect(submit).toHaveBeenCalledWith("Mayor");
  });

  it("Tab does not choose and Escape only closes the popup", async () => {
    request.mockResolvedValue({ available: true, items: [suggestion] });
    const select = vi.fn(); const escape = vi.fn();
    render(<div onKeyDown={escape}><Host select={select} /></div>);
    await search();
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "ArrowDown" });
    escape.mockClear();
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Escape" });
    expect(escape).not.toHaveBeenCalled();
    expect(screen.queryByRole("listbox")).toBeNull();
    await search("Mayor 12");
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "ArrowDown" });
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Tab" });
    expect(select).not.toHaveBeenCalled();
    expect(screen.queryByRole("listbox")).toBeNull();
  });
});
