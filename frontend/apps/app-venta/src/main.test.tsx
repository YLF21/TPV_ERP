// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { LocaleCode, UserSession } from "../../../packages/app-common/src/types";
import { saleUserLocaleStorageKey } from "./saleUserLocale";

const session: UserSession = {
  userId: " CASHIER-1 ",
  username: "cashier",
  displayName: "Cashier",
  permissions: [],
};

vi.mock("react-dom/client", () => ({
  createRoot: vi.fn(() => ({ render: vi.fn() })),
}));

vi.mock("../../../packages/app-common/src/components/LoginScreen", () => ({
  LoginScreen: ({
    locale,
    onLocaleChange,
    onLogin,
  }: {
    locale: LocaleCode;
    onLocaleChange: (locale: LocaleCode) => void;
    onLogin: (session: UserSession) => void;
  }) => (
    <section aria-label="login">
      <output aria-label="login locale">{locale}</output>
      <button type="button" onClick={() => onLocaleChange("zh")}>Change login locale</button>
      <button type="button" onClick={() => onLogin(session)}>Log in</button>
    </section>
  ),
}));

vi.mock("../../../packages/app-common/src/components/SessionHomeScreen", () => ({
  SessionHomeScreen: ({
    locale,
    onLocaleChange,
    onLogout,
  }: {
    locale: LocaleCode;
    onLocaleChange: (locale: LocaleCode) => void;
    onLogout: () => void;
  }) => (
    <section aria-label="home">
      <output aria-label="home locale">{locale}</output>
      <button type="button" onClick={() => onLocaleChange("zh")}>Change home locale</button>
      <button type="button" onClick={onLogout}>Log out</button>
    </section>
  ),
}));

import { App } from "./main";

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe("APP VENTA locale wiring", () => {
  it("loads the user's preference on login, persists changes, and resets to Spanish on logout", () => {
    localStorage.setItem(saleUserLocaleStorageKey(session), "en");
    render(<App />);

    expect(screen.getByLabelText("login locale")).toHaveTextContent("es");
    fireEvent.click(screen.getByRole("button", { name: "Log in" }));
    expect(screen.getByLabelText("home locale")).toHaveTextContent("en");

    fireEvent.click(screen.getByRole("button", { name: "Change home locale" }));
    expect(screen.getByLabelText("home locale")).toHaveTextContent("zh");
    expect(localStorage.getItem(saleUserLocaleStorageKey(session))).toBe("zh");

    fireEvent.click(screen.getByRole("button", { name: "Log out" }));
    expect(screen.getByLabelText("login locale")).toHaveTextContent("es");
  });
});
