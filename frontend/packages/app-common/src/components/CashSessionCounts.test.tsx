// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { SaleCashSessionDialog } from "./SaleCashSessionDialog";
import { SaleTouchKeyboardScope } from "./SaleTouchKeyboardScope";
afterEach(cleanup);

function requestFor(status = "CERRADA") {
  return vi.fn(async () => ({ id:"cash-1", terminalId:"terminal-1", status, openedAt:"2026-10-01T08:00:00Z", openingFund:235, closedByAttempt:true }));
}
function dialog(request: ReturnType<typeof requestFor>, mode:"OPEN"|"CLOSE" = "CLOSE", required = false) {
  render(<SaleCashSessionDialog locale="es" currentUsername="ADMIN" terminalId="terminal-1" token="token"
    mode={mode} request={request as unknown as typeof apiRequest} requireClosingBreakdown={required} requireWithdrawalBreakdown={required} />);
}
describe("cash counts connected to opening and closing", () => {
  it("requires the manual count and sends it instead of a calculated opening", async () => {
    const request=requestFor("ABIERTA"); dialog(request,"OPEN");
    const submit=screen.getByRole("button",{name:"Abrir caja"});
    expect(submit).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Efectivo contado / fondo inicial"),"235,00");
    await userEvent.click(submit);
    expect(request).toHaveBeenCalledWith("/cash/sessions/open",expect.objectContaining({body:{
      terminalId:"terminal-1",countedFund:235,denominations:[],
    }}));
  });
  it("applies each denomination count to its own closing field and sends both breakdowns", async () => {
    const request=requestFor(); dialog(request,"CLOSE",true);
    await userEvent.click(screen.getAllByRole("button",{name:"Contar monedas y billetes"})[0]);
    let counter=await screen.findByRole("dialog",{name:"Retirada final"});
    for(const [value,quantity] of [[100,4],[50,3],[20,5]]) {
      await userEvent.type(within(counter).getByRole("spinbutton",{name:new RegExp("^Unidades "+value+",00")}),String(quantity));
    }
    await userEvent.click(within(counter).getByRole("button",{name:"Aceptar"}));
    expect(screen.getByLabelText("Retirada final")).toHaveValue("650.00");
    expect(screen.getByLabelText("Fondo que queda en caja")).toHaveValue("0");
    await userEvent.click(screen.getAllByRole("button",{name:"Contar monedas y billetes"})[1]);
    counter=await screen.findByRole("dialog",{name:"Fondo que queda en caja"});
    for(const [value,quantity] of [[50,2],[20,3],[10,2],[5,2],[2,4],[1,2]]) {
      await userEvent.type(within(counter).getByRole("spinbutton",{name:new RegExp("^Unidades "+value+",00")}),String(quantity));
    }
    await userEvent.click(within(counter).getByRole("button",{name:"Aceptar"}));
    expect(screen.getByLabelText("Fondo que queda en caja")).toHaveValue("200.00");
    await userEvent.click(screen.getByRole("button",{name:"Cerrar caja"}));
    expect(request).toHaveBeenCalledWith("/cash/sessions/close",expect.objectContaining({body:expect.objectContaining({
      retainedFund:200,finalWithdrawalAmount:650,
      finalWithdrawalDenominations:[{denomination:100,quantity:4},{denomination:50,quantity:3},{denomination:20,quantity:5}],
      retainedFundDenominations:[{denomination:50,quantity:2},{denomination:20,quantity:3},{denomination:10,quantity:2},{denomination:5,quantity:2},{denomination:2,quantity:4},{denomination:1,quantity:2}],
    })}));
  });
  it("blocks positive amounts without mandatory breakdown and clears a stale count after typing", async () => {
    const request=requestFor(); dialog(request,"CLOSE",true);
    await userEvent.clear(screen.getByLabelText("Fondo que queda en caja"));
    await userEvent.type(screen.getByLabelText("Fondo que queda en caja"),"10");
    await userEvent.click(screen.getByRole("button",{name:"Cerrar caja"}));
    expect(await screen.findByRole("alert")).toHaveTextContent("Completa el recuento");
    expect(request).not.toHaveBeenCalled();
  });
  it("keeps denominations in the recovery envelope after an uncertain close", async () => {
    const request=vi.fn().mockRejectedValue(new Error("Respuesta desconocida"));
    const flows=vi.fn();
    render(<SaleCashSessionDialog locale="es" currentUsername="ADMIN" terminalId="terminal-1" token="token"
      mode="CLOSE" request={request as typeof apiRequest} denominations={[20]} onCloseFlowChange={flows}/>);
    await userEvent.click(screen.getAllByRole("button",{name:"Contar monedas y billetes"})[0]);
    const counter=await screen.findByRole("dialog",{name:"Retirada final"});
    await userEvent.type(within(counter).getByRole("spinbutton",{name:/Unidades 20/}),"2");
    await userEvent.click(within(counter).getByRole("button",{name:"Aceptar"}));
    await userEvent.click(screen.getByRole("button",{name:"Cerrar caja"}));
    await screen.findByRole("alert");
    expect(flows).toHaveBeenLastCalledWith(expect.objectContaining({
      phase:"ATTEMPTED", finalWithdrawalDenominations:[{denomination:20,quantity:2}],
    }));
    expect(screen.getByLabelText("Retirada final")).toBeDisabled();
    expect(screen.getAllByRole("button",{name:"Contar monedas y billetes"})[0]).toBeDisabled();
  });
  it("honours a withdrawal-only breakdown policy without requiring a retained count", async () => {
    const request=requestFor();
    render(<SaleCashSessionDialog locale="es" currentUsername="ADMIN" terminalId="terminal-1" token="token"
      mode="CLOSE" request={request as unknown as typeof apiRequest} requireWithdrawalBreakdown
      denominations={[5]} withdrawalDenominations={[20]} />);
    await userEvent.clear(screen.getByLabelText("Fondo que queda en caja"));
    await userEvent.type(screen.getByLabelText("Fondo que queda en caja"),"10");
    await userEvent.click(screen.getAllByRole("button",{name:"Contar monedas y billetes"})[0]);
    const counter=await screen.findByRole("dialog",{name:"Retirada final"});
    expect(within(counter).queryByRole("spinbutton",{name:/Unidades 5,/})).not.toBeInTheDocument();
    await userEvent.type(within(counter).getByRole("spinbutton",{name:/Unidades 20/}),"2");
    await userEvent.click(within(counter).getByRole("button",{name:"Aceptar"}));
    await userEvent.click(screen.getByRole("button",{name:"Cerrar caja"}));
    expect(request).toHaveBeenCalledWith("/cash/sessions/close",expect.objectContaining({body:expect.objectContaining({
      retainedFund:10,retainedFundDenominations:[],finalWithdrawalAmount:40,
      finalWithdrawalDenominations:[{denomination:20,quantity:2}],
    })}));
  });
  it("applies a touch count with two final Enter presses without closing the cash session", async () => {
    const user=userEvent.setup();
    const request=requestFor();
    render(<SaleTouchKeyboardScope locale="es" interfaceMode="TOUCH"><SaleCashSessionDialog locale="es" interfaceMode="TOUCH" currentUsername="ADMIN" terminalId="terminal-1" token="token"
      mode="CLOSE" request={request as unknown as typeof apiRequest} withdrawalDenominations={[100,0.01]} /></SaleTouchKeyboardScope>);
    await user.click(screen.getAllByRole("button",{name:"Contar monedas y billetes"})[0]);
    const counter=await screen.findByRole("dialog",{name:"Retirada final"});
    expect(within(counter).getByRole("button",{name:"Siguiente"})).toBeInTheDocument();
    const fields=within(counter).getAllByRole("spinbutton");
    await user.type(fields[0],"1{Enter}");
    expect(fields[1]).toHaveFocus();
    await user.keyboard("2{Enter}");
    expect(within(counter).getByRole("button",{name:"Aceptar"})).toHaveFocus();
    expect(screen.getByLabelText("Retirada final",{selector:"input"})).toHaveValue("0");
    await user.keyboard("{Enter}");
    expect(screen.queryByRole("dialog",{name:"Retirada final"})).not.toBeInTheDocument();
    expect(screen.getByLabelText("Retirada final")).toHaveValue("100.02");
    expect(screen.getByLabelText("Fondo que queda en caja")).toHaveValue("0");
    expect(request).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button",{name:"Cerrar caja"}));
    expect(request).toHaveBeenCalledWith("/cash/sessions/close",expect.objectContaining({body:expect.objectContaining({
      retainedFund:0,finalWithdrawalAmount:100.02,
      finalWithdrawalDenominations:[{denomination:100,quantity:1},{denomination:0.01,quantity:2}],
    })}));
  });
});
