// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { useState } from "react";
import { createPortal } from "react-dom";
import { cleanup, fireEvent, render, screen, within, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SaleTouchKeyboardScope } from "./SaleTouchKeyboardScope";
import { TouchAlphaKeyboard } from "./TouchAlphaKeyboard";

afterEach(cleanup);

function Form({ touch = true, portal = false, disabled = false, onSubmit = vi.fn() }) {
  const [name, setName] = useState("ABC");
  const [password, setPassword] = useState("");
  const [email, setEmail] = useState("a@example.com");
  const [qty, setQty] = useState("1");
  const [notes, setNotes] = useState("");
  const [open, setOpen] = useState(true);
  const dialog = open && <section role="dialog" aria-label="Formulario">
    <form onSubmit={onSubmit}>
      <label>Nombre<input value={name} maxLength={5} disabled={disabled} onChange={(e) => setName(e.target.value)} /></label>
      <label>Contraseña<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
      <label>Correo<input type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></label>
      <label>Cantidad<input type="number" step="0.001" value={qty} onChange={(e) => setQty(e.target.value)} /></label>
      <label>Notas<textarea value={notes} onChange={(e) => setNotes(e.target.value)} /></label>
      <label>Fecha<input type="date" /></label>
      <label>Fijo<input readOnly value="FIJO" /></label>
      <label>Externo<input data-touch-keyboard="off" /></label>
      <button type="button" onClick={() => setOpen(false)}>Cancelar formulario</button>
    </form>
  </section>;
  return <SaleTouchKeyboardScope locale="es" interfaceMode={touch ? "TOUCH" : "KEYBOARD"}>
    <input aria-label="Escáner principal" />
    {portal ? createPortal(dialog, document.body) : dialog}
  </SaleTouchKeyboardScope>;
}

describe("SaleTouchKeyboardScope", () => {
  it.each([false, true])("offers a collapsed launcher before focusing a field (portal=%s)", (portal) => {
    const submit = vi.fn();
    render(<Form portal={portal} onSubmit={submit} />);
    expect(screen.getByRole("button", { name: "Mostrar teclado" })).toBeVisible();
    expect(screen.queryByRole("group", { name: "Teclado alfanumérico" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Mostrar teclado" }));
    fireEvent.click(screen.getByRole("button", { name: "Q" }));
    expect(screen.getByLabelText("Nombre")).toHaveValue("ABCQ");
    expect(submit).not.toHaveBeenCalled();
  });

  it("retains the launcher and switches safely when the active field is removed", async () => {
    function ReplacedField() {
      const [replace, setReplace] = useState(false);
      const [value, setValue] = useState("");
      return <SaleTouchKeyboardScope locale="es" interfaceMode="TOUCH"><section role="dialog">
        {!replace && <input autoFocus aria-label="Anterior" />}
        <input aria-label="Nuevo" value={value} onChange={(e) => setValue(e.target.value)} />
        <button onClick={() => setReplace(true)}>Reemplazar campo</button>
      </section></SaleTouchKeyboardScope>;
    }
    render(<ReplacedField />);
    fireEvent.click(screen.getByRole("button", { name: "Reemplazar campo" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Mostrar teclado" })).toBeVisible());
    fireEvent.click(screen.getByRole("button", { name: "Mostrar teclado" }));
    fireEvent.click(screen.getByRole("button", { name: "Q" }));
    expect(screen.getByLabelText("Nuevo")).toHaveValue("Q");
  });

  it("keeps a launcher across nested windows without editing the inactive parent", async () => {
    function NestedWindow() {
      const [nested, setNested] = useState(false);
      const [parent, setParent] = useState("");
      const [child, setChild] = useState("");
      return <SaleTouchKeyboardScope locale="es" interfaceMode="TOUCH">
        <section role="dialog" aria-label="Principal" inert={nested || undefined}>
          <input aria-label="Padre" value={parent} onChange={(e) => setParent(e.target.value)} />
          <button onClick={() => setNested(true)}>Abrir otra ventana</button>
        </section>
        {nested && createPortal(<section role="dialog" aria-label="Secundaria">
          <input aria-label="Hijo" value={child} onChange={(e) => setChild(e.target.value)} />
          <button onClick={() => setNested(false)}>Cerrar otra ventana</button>
        </section>, document.body)}
      </SaleTouchKeyboardScope>;
    }
    render(<NestedWindow />);
    fireEvent.click(screen.getByRole("button", { name: "Abrir otra ventana" }));
    const childDialog = screen.getByRole("dialog", { name: "Secundaria" });
    await waitFor(() => expect(within(childDialog).getByRole("button", { name: "Mostrar teclado" })).toBeVisible());
    fireEvent.click(within(childDialog).getByRole("button", { name: "Mostrar teclado" }));
    fireEvent.click(within(childDialog).getByRole("button", { name: "Q" }));
    expect(screen.getByLabelText("Hijo")).toHaveValue("Q");
    expect(screen.getByLabelText("Padre")).toHaveValue("");
    fireEvent.click(screen.getByRole("button", { name: "Cerrar otra ventana" }));
    await waitFor(() => expect(within(screen.getByRole("dialog", { name: "Principal" }))
      .getByRole("button", { name: "Mostrar teclado" })).toBeVisible());
  });

  it("keeps the toggle available while collapsed and restores the same field and cursor without submitting", () => {
    const onSubmit = vi.fn();
    render(<Form onSubmit={onSubmit} />);
    const input = screen.getByLabelText<HTMLInputElement>("Nombre");
    fireEvent.focus(input);
    input.setSelectionRange(1, 2);
    const close = screen.getByRole("button", { name: "Cerrar teclado" });
    expect(close).toHaveAttribute("aria-expanded", "true");
    fireEvent.click(close);
    expect(screen.queryByRole("group", { name: "Teclado alfanumérico" })).not.toBeInTheDocument();
    const open = screen.getByRole("button", { name: "Mostrar teclado" });
    expect(open).toHaveAttribute("aria-expanded", "false");
    expect(input).toHaveValue("ABC");
    fireEvent.click(open);
    expect(screen.getByRole("group", { name: "Teclado alfanumérico" })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Z" }));
    expect(input).toHaveValue("AZC");
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("preserves an incomplete numeric draft across hiding and reopening", () => {
    render(<Form />);
    fireEvent.focus(screen.getByLabelText("Cantidad"));
    fireEvent.click(screen.getByRole("button", { name: "2" }));
    fireEvent.click(screen.getByRole("button", { name: "," }));
    fireEvent.click(screen.getByRole("button", { name: "Cerrar teclado" }));
    expect(screen.queryByRole("group", { name: "Teclado numérico" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Mostrar teclado" }));
    fireEvent.click(screen.getByRole("button", { name: "3" }));
    expect(screen.getByLabelText("Cantidad")).toHaveValue(2.3);
  });

  it("writes through React onChange, preserves selection and maxlength, and never submits", () => {
    const onSubmit = vi.fn();
    render(<Form onSubmit={onSubmit} />);
    const input = screen.getByLabelText("Nombre") as HTMLInputElement;
    fireEvent.focus(input);
    input.setSelectionRange(1, 2);
    fireEvent.click(screen.getByRole("button", { name: "Z" }));
    expect(input).toHaveValue("AZC");
    fireEvent.click(screen.getByRole("button", { name: "1" }));
    expect(input).toHaveValue("AZ1C");
    fireEvent.click(screen.getByRole("button", { name: "2" }));
    fireEvent.click(screen.getByRole("button", { name: "3" }));
    expect(input).toHaveValue("AZ12C");
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("targets the active password and textarea without rendering credential text", () => {
    render(<Form />);
    const password = screen.getByLabelText("Contraseña");
    fireEvent.focus(password);
    fireEvent.click(screen.getByRole("button", { name: "Q" }));
    expect(password).toHaveValue("Q");
    expect(password).toHaveAttribute("type", "password");
    fireEvent.focus(screen.getByLabelText("Notas"));
    fireEvent.click(screen.getByRole("button", { name: "W" }));
    expect(screen.getByLabelText("Notas")).toHaveValue("W");
    expect(password).toHaveValue("Q");
    expect(screen.getByLabelText("Nombre")).toHaveValue("ABC");
  });

  it("uses numeric keys for numbers, replaces the initially selected number and preserves decimals", () => {
    render(<Form />);
    fireEvent.focus(screen.getByLabelText("Cantidad"));
    expect(screen.getByRole("group", { name: "Teclado numérico" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "2" }));
    expect(screen.getByLabelText("Cantidad")).toHaveValue(2);
    fireEvent.click(screen.getByRole("button", { name: "," }));
    fireEvent.click(screen.getByRole("button", { name: "3" }));
    // A native number input may normalize a trailing decimal; the next digit must not become 23.
    expect(screen.getByLabelText("Cantidad")).toHaveValue(2.3);
  });

  it("edits a native email field through the contextual keyboard and retains email validation", () => {
    const onSubmit = vi.fn();
    render(<Form onSubmit={onSubmit} />);
    const input = screen.getByLabelText<HTMLInputElement>("Correo");
    fireEvent.focus(input);
    fireEvent.click(screen.getByRole("button", { name: "Limpiar todo" }));
    fireEvent.click(screen.getByRole("button", { name: "A" }));
    expect(input.validity.typeMismatch).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Más símbolos" }));
    fireEvent.click(screen.getByRole("button", { name: "@" }));
    fireEvent.click(screen.getByRole("button", { name: "Más símbolos" }));
    fireEvent.click(screen.getByRole("button", { name: "B" }));
    fireEvent.click(screen.getByRole("button", { name: "." }));
    fireEvent.click(screen.getByRole("button", { name: "C" }));
    expect(input).toHaveValue("A@B.C");
    expect(input).toHaveAttribute("type", "email");
    expect(input.validity.typeMismatch).toBe(false);
    expect(input).toHaveFocus();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("does not add a keypad to the main scanner, native dates, readonly or opted-out controls", () => {
    render(<Form />);
    for (const label of ["Escáner principal", "Fecha", "Fijo", "Externo"]) {
      fireEvent.focus(screen.getByLabelText(label));
      expect(screen.queryByRole("group", { name: "Teclado alfanumérico" })).not.toBeInTheDocument();
      expect(screen.queryByRole("group", { name: "Teclado numérico" })).not.toBeInTheDocument();
    }
  });

  it("continues physical numeric entry without replacing it or retaining an old decimal draft", () => {
    render(<Form />);
    const input = screen.getByLabelText("Cantidad");
    fireEvent.focus(input);
    fireEvent.input(input, { target: { value: "12" } });
    fireEvent.click(screen.getByRole("button", { name: "3" }));
    expect(input).toHaveValue(123);
    fireEvent.click(screen.getByRole("button", { name: "," }));
    fireEvent.input(input, { target: { value: "4" } });
    fireEvent.click(screen.getByRole("button", { name: "5" }));
    expect(input).toHaveValue(45);
  });

  it("is opt-in and closes when the mode changes or a field becomes disabled", async () => {
    const { rerender } = render(<Form touch={false} />);
    fireEvent.focus(screen.getByLabelText("Nombre"));
    expect(screen.queryByRole("group", { name: "Teclado alfanumérico" })).not.toBeInTheDocument();
    rerender(<Form />);
    fireEvent.focus(screen.getByLabelText("Nombre"));
    expect(screen.getByRole("group", { name: "Teclado alfanumérico" })).toBeInTheDocument();
    rerender(<Form disabled />);
    expect(await screen.findByLabelText("Nombre")).toBeDisabled();
    rerender(<Form touch={false} />);
    expect(screen.queryByRole("group", { name: "Teclado alfanumérico" })).not.toBeInTheDocument();
  });

  it("supports portal dialogs and removes the dock on cancel without a submit", () => {
    const onSubmit = vi.fn();
    render(<Form portal onSubmit={onSubmit} />);
    fireEvent.focus(screen.getByLabelText("Nombre"));
    expect(within(screen.getByRole("dialog")).getByRole("group", { name: "Teclado alfanumérico" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cancelar formulario" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Teclado alfanumérico" })).not.toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("does not duplicate an explicitly managed keyboard", () => {
    render(<SaleTouchKeyboardScope locale="es" interfaceMode="TOUCH"><section role="dialog">
      <input aria-label="Consulta" /><TouchAlphaKeyboard locale="es" value="" onChange={vi.fn()} />
    </section></SaleTouchKeyboardScope>);
    fireEvent.focus(screen.getByLabelText("Consulta"));
    expect(screen.getAllByRole("group", { name: "Teclado alfanumérico" })).toHaveLength(1);
    expect(document.querySelector(".sale-touch-field-keyboard")).toBeNull();
  });
});
