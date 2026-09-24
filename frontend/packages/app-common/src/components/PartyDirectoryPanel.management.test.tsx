// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import {cleanup, fireEvent, render, screen, within, waitFor} from "@testing-library/react";
import {afterEach, beforeEach, expect, it, vi} from "vitest";
import {apiRequest} from "../api/client";
import {PartyDirectoryPanel} from "./PartyDirectoryPanel";
vi.mock("../api/client", async (original) => ({...await original<typeof import("../api/client")>(), apiRequest:vi.fn()}));
const session = {username:"ui",displayName:"UI",accessToken:"test",permissions:["ADMIN" as const]};
beforeEach(() => { localStorage.clear(); vi.mocked(apiRequest).mockReset().mockImplementation(async(path) => path.includes("/management/page") ? {items:[{id:"one",clientId:"C-1",fiscalName:"CLIENTE",documentType:"NIF",documentNumber:"12345678Z",active:true}],hasMore:false} : []); });
afterEach(cleanup);
it("requires explicit application confirmation before deactivating a customer", async () => {
 render(<PartyDirectoryPanel app="gestion" kind="customers" locale="es" session={session} allowSafeRetirement/>);
 fireEvent.doubleClick(await screen.findByText("CLIENTE"));
 fireEvent.click(screen.getByRole("button",{name:"Modificar cliente (F7)"}));
 fireEvent.click(screen.getByRole("button",{name:"Desactivar"}));
 let dialog=screen.getByRole("alertdialog");
 expect(apiRequest).not.toHaveBeenCalledWith("/customers/one/deactivate",expect.anything());
 fireEvent.click(within(dialog).getByRole("button",{name:"Cancelar"}));
 expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole("button",{name:"Desactivar"}));
 dialog=screen.getByRole("alertdialog");
 fireEvent.click(within(dialog).getByRole("button",{name:"Confirmar"}));
 await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/customers/one/deactivate",expect.objectContaining({method:"PATCH"})));
});
it("keeps unsaved data when dismissing the discard confirmation", async () => {
 render(<PartyDirectoryPanel app="gestion" kind="customers" locale="es" session={session} allowSafeRetirement/>);
 await screen.findByText("CLIENTE");
 fireEvent.click(screen.getByRole("button",{name:"F8 Nuevo cliente"}));
 const input=screen.getByRole("textbox",{name:"Nombre o razón social"});
 fireEvent.change(input,{target:{value:"Pendiente"}});
 fireEvent.click(screen.getByRole("button",{name:"Cerrar"}));
 fireEvent.keyDown(screen.getByRole("alertdialog"),{key:"Escape"});
 expect(input).toHaveValue("Pendiente");
 expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
});

it("selects suppliers and routes F9 to review without retiring immediately", async () => {
 vi.mocked(apiRequest).mockImplementation(async path => path.includes("/management/page") ? {items:[{id:"s1",supplierId:"S-1",legalName:"SUPPLIER",active:true}],hasMore:false} : path.includes("retirement-impact") ? {id:"s1",version:1,currentState:"ACTIVE",outcomeIfConfirmed:"HARD_DELETED",reasonCodes:[],executable:true} : []);
 render(<PartyDirectoryPanel app="gestion" kind="suppliers" locale="es" session={session} allowSafeRetirement/>);
 expect(screen.getByRole("button",{name:"F7 Modificar proveedor"})).toBeDisabled();
 fireEvent.click(await screen.findByText("SUPPLIER"),{detail:1});
 expect(screen.getByRole("button",{name:"F7 Modificar proveedor"})).toBeEnabled();
 fireEvent.keyDown(window,{key:"F9"});
 await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/suppliers/management/s1/retirement-impact",expect.anything()));
 expect(vi.mocked(apiRequest).mock.calls.some(([path]) => path.endsWith("/retire"))).toBe(false);
});
it("opens the selected customer editor with F7 and ignores list shortcuts inside a dialog", async () => {
 render(<PartyDirectoryPanel app="gestion" kind="customers" locale="es" session={session} allowSafeRetirement/>);
 fireEvent.click(await screen.findByText("CLIENTE"),{detail:1});
 fireEvent.keyDown(window,{key:"F7"});
 expect(screen.getByRole("textbox",{name:"Nombre o razón social"})).toHaveValue("CLIENTE");
 fireEvent.keyDown(window,{key:"F8"});
 expect(screen.getByRole("textbox",{name:"Nombre o razón social"})).toHaveValue("CLIENTE");
});
