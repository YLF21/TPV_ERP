// @vitest-environment jsdom
import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { createTranslator } from "../i18n/LocalizedMessages";
import { ErpListFilterPanel } from "./ErpListFilterPanel";
import { ErpSelect } from "./ErpSelect";

afterEach(cleanup);
const t = createTranslator("es");

it("folds criteria without clearing their values or invoking screen actions", () => {
  const action = vi.fn();
  function Example() {
    const [value, setValue] = useState("initial");
    return <ErpListFilterPanel translate={t} search={<label>Buscar<input type="search" /></label>}
      filters={<label>Referencia<input value={value} onChange={event => setValue(event.target.value)} /></label>}
      actions={<button type="button" onClick={action}>Excel</button>} />;
  }
  render(<Example />);
  const toggle = screen.getByRole("button", { name: "Filtrar" });
  expect(toggle.getAttribute("aria-expanded")).toBe("false");
  fireEvent.click(toggle);
  fireEvent.change(screen.getByRole("textbox", { name: "Referencia" }), { target: { value: "changed" } });
  fireEvent.keyDown(screen.getByRole("textbox", { name: "Referencia" }), { key: "Escape" });
  expect(toggle.getAttribute("aria-expanded")).toBe("false");
  expect(document.activeElement).toBe(toggle);
  fireEvent.click(toggle);
  expect((screen.getByRole("textbox", { name: "Referencia" }) as HTMLInputElement).value).toBe("changed");
  expect(action).not.toHaveBeenCalled();
});

it("lets Escape close an option menu before folding the criteria", () => {
  render(<ErpListFilterPanel translate={t} search={<input type="search" aria-label="Buscar" />}
    filters={<ErpSelect aria-label="Estado" value="" options={[{ value: "", label: "Todos" }]} onChange={vi.fn()} />} />);
  const toggle = screen.getByRole("button", { name: "Filtrar" });
  fireEvent.click(toggle);
  const select = screen.getAllByRole("button", { name: "Estado" }).find(element => element.getAttribute("aria-haspopup") === "listbox")!;
  fireEvent.click(select);
  fireEvent.keyDown(screen.getByRole("option", { name: "Todos" }), { key: "Escape" });
  expect(screen.queryByRole("listbox")).toBeNull();
  expect(toggle.getAttribute("aria-expanded")).toBe("true");
});
