// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { UserSession } from "../types";
import { CustomerDocumentValidation, ExistingCustomerNotice } from "./CustomerDocumentValidation";

vi.mock("../api/client", async (original) => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));

const request = vi.mocked(apiRequest);
const session: UserSession = { username: "test", displayName: "Test", accessToken: "token", permissions: ["CUSTOMERS_WRITE"] };
const identity = { documentType: "CIF", documentNumber: "B12345674" };
const customer = { centralCode: "C-001", documentNumber: "B12345674" };

function props(overrides: Partial<React.ComponentProps<typeof CustomerDocumentValidation>> = {}) {
  return {
    ...identity,
    session,
    locale: "es" as const,
    disabled: false,
    onBusyChange: vi.fn(),
    onAvailable: vi.fn(),
    onExisting: vi.fn(),
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

beforeEach(() => { request.mockReset(); });
afterEach(() => cleanup());

describe("CustomerDocumentValidation", () => {
  it("posts the normalized identity and reports an existing customer without adopting or creating it", async () => {
    request.mockResolvedValue(customer);
    const input = props();
    const t = createTranslator("es");
    render(<CustomerDocumentValidation {...input} />);

    await act(async () => { fireEvent.click(screen.getByRole("button", { name: t("party.customerEditor.validate") })); });

    await waitFor(() => expect(input.onExisting).toHaveBeenCalledExactlyOnceWith(customer));
    expect(request).toHaveBeenCalledExactlyOnceWith("/customers/central-lookup", expect.objectContaining({
      token: "token",
      body: { documentType: "NIF", documentNumber: "B12345674" },
    }));
    const [path, options] = request.mock.calls[0];
    expect(path).toBe("/customers/central-lookup");
    expect(options?.method ?? (options?.body === undefined ? "GET" : "POST")).toBe("POST");
    expect(input.onAvailable).not.toHaveBeenCalled();
    expect(request.mock.calls.some(([calledPath]) => calledPath === "/customers" || calledPath === "/customers/adopt-central")).toBe(false);
  });

  it("marks the normalized identity available only when SaaS reports not found", async () => {
    request.mockRejectedValue(new ApiError("not found", 404, { code: "CUSTOMER_CENTRAL_NOT_FOUND" }));
    const input = props();
    const t = createTranslator("es");
    render(<CustomerDocumentValidation {...input} />);

    await act(async () => { fireEvent.click(screen.getByRole("button", { name: t("party.customerEditor.validate") })); });

    expect(await screen.findByRole("status")).toHaveTextContent(t("party.customerEditor.available"));
    expect(input.onAvailable).toHaveBeenCalledExactlyOnceWith("NIF:B12345674");
    expect(input.onExisting).not.toHaveBeenCalled();
  });

  it("does not mark the identity available after a service error", async () => {
    request.mockRejectedValue(new ApiError("unavailable", 503, { code: "CUSTOMER_IDENTITY_SAAS_UNAVAILABLE" }));
    const input = props();
    const t = createTranslator("es");
    render(<CustomerDocumentValidation {...input} />);

    fireEvent.click(screen.getByRole("button", { name: t("party.customerEditor.validate") }));

    expect(await screen.findByRole("alert")).toHaveTextContent(t("party.customerIdentity.unavailable"));
    expect(input.onAvailable).not.toHaveBeenCalled();
    expect(input.onExisting).not.toHaveBeenCalled();
  });

  it("does not mark the identity available after an unknown error", async () => {
    request.mockRejectedValue(new Error("unexpected failure"));
    const input = props();
    const t = createTranslator("es");
    render(<CustomerDocumentValidation {...input} />);

    fireEvent.click(screen.getByRole("button", { name: t("party.customerEditor.validate") }));

    expect(await screen.findByRole("alert")).toHaveTextContent(t("party.customerEditor.validationError"));
    expect(input.onAvailable).not.toHaveBeenCalled();
    expect(input.onExisting).not.toHaveBeenCalled();
  });

  it("disables validation for an empty document or a disabled form", () => {
    const t = createTranslator("es");
    const empty = props({ documentNumber: "   " });
    const emptyView = render(<CustomerDocumentValidation {...empty} />);
    expect(screen.getByRole("button", { name: t("party.customerEditor.validate") })).toBeDisabled();
    emptyView.unmount();

    render(<CustomerDocumentValidation {...props({ disabled: true })} />);
    expect(screen.getByRole("button", { name: t("party.customerEditor.validate") })).toBeDisabled();
    expect(request).not.toHaveBeenCalled();
  });

  it("keeps validation disabled while the lookup is busy", async () => {
    const pending = deferred<typeof customer>();
    request.mockReturnValue(pending.promise);
    const input = props();
    const t = createTranslator("es");
    render(<CustomerDocumentValidation {...input} />);
    const button = screen.getByRole("button", { name: t("party.customerEditor.validate") });

    fireEvent.click(button);

    expect(button).toBeDisabled();
    expect(input.onBusyChange).toHaveBeenCalledWith(true);
    await act(async () => pending.resolve(customer));
    await waitFor(() => expect(button).toBeEnabled());
    expect(input.onBusyChange).toHaveBeenLastCalledWith(false);
  });

  it.each(["change", "unmount"] as const)("aborts and ignores a late lookup response after %s", async (operation) => {
    const pending = deferred<typeof customer>();
    request.mockReturnValue(pending.promise);
    const input = props();
    const t = createTranslator("es");
    const view = render(<CustomerDocumentValidation {...input} />);
    fireEvent.click(screen.getByRole("button", { name: t("party.customerEditor.validate") }));
    const signal = request.mock.calls[0][1]?.signal;
    expect(signal).toBeInstanceOf(AbortSignal);

    if (operation === "change") view.rerender(<CustomerDocumentValidation {...input} documentNumber="87654321X" />);
    else view.unmount();
    expect(signal?.aborted).toBe(true);

    await act(async () => pending.resolve(customer));
    expect(input.onExisting).not.toHaveBeenCalled();
    expect(input.onAvailable).not.toHaveBeenCalled();
  });
});

describe("ExistingCustomerNotice", () => {
  it("shows the central code and document and accepts through its only button", () => {
    const onAccept = vi.fn();
    const t = createTranslator("es");
    render(<ExistingCustomerNotice customer={customer} locale="es" onAccept={onAccept} />);

    const notice = screen.getByRole("alertdialog", { name: t("party.customerEditor.existsTitle") });
    expect(notice).toHaveTextContent(customer.centralCode);
    expect(notice).toHaveTextContent(customer.documentNumber);
    const buttons = screen.getAllByRole("button");
    expect(buttons).toHaveLength(1);
    expect(buttons[0]).toHaveAccessibleName(t("common.accept"));
    fireEvent.click(buttons[0]);
    expect(onAccept).toHaveBeenCalledTimes(1);
  });
});
