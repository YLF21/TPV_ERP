// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AppFrame } from "./AppFrame";

const session = {
  username: "admin",
  displayName: "Administrador",
  permissions: ["ADMIN" as const]
};

describe("AppFrame language selector", () => {
  it("offers all supported languages and reports the selected locale", () => {
    const onLocaleChange = vi.fn();

    render(
      <AppFrame
        titleKey="gestion.title"
        locale="es"
        session={session}
        onLocaleChange={onLocaleChange}
        onLogout={vi.fn()}
      >
        <div>Contenido</div>
      </AppFrame>
    );

    fireEvent.click(screen.getByRole("button", { name: "Cambiar idioma" }));
    expect(screen.getByRole("button", { name: /Español/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: /English/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /中文/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /English/ }));

    expect(onLocaleChange).toHaveBeenCalledWith("en");
    expect(screen.queryByRole("button", { name: /English/ })).not.toBeInTheDocument();
  });
});
