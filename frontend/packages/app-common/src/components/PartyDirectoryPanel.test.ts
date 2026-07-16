import { describe, expect, it } from "vitest";
import { buildPartyRequest, customerReceivablesActionVisible, emptyPartyForm, partyFormFromView, validatePartyForm } from "./PartyDirectoryPanel";

describe("PartyDirectoryPanel", () => {
  it("builds the complete customer request", () => {
    expect(buildPartyRequest({
      ...emptyPartyForm, name: " Cliente Uno ", documentType: "NIF", documentNumber: " 123A ",
      birthday: "1990-05-12", gender: "FEMENINO", discount: "7.5", commercialConsent: true,
      preferredCommercialChannelId: "channel-1", country: "es"
    }, false, false)).toMatchObject({
      fiscalName: "Cliente Uno", documentNumber: "123A", birthday: "1990-05-12", gender: "FEMENINO",
      discount: 7.5, commercialConsent: true, preferredCommercialChannelId: "channel-1",
      address: { country: "ES" }, isMember: false
    });
  });

  it("builds only fiscal and contact data for suppliers", () => {
    const request = buildPartyRequest({
      ...emptyPartyForm, name: "Proveedor SL", tradeName: "Proveedor", documentNumber: "B123",
      birthday: "1990-01-01", commercialConsent: true
    }, true, false);
    expect(request).toEqual({
      legalName: "Proveedor SL", tradeName: "Proveedor", documentType: "NIF", documentNumber: "B123",
      address: { address: null, postalCode: null, city: null, province: null, country: "ES" },
      phone: null, email: null, notes: null
    });
  });

  it("restores persisted customer fields for editing", () => {
    expect(partyFormFromView({
      id: "c1", clientId: "C-01-1", fiscalName: "Ana", documentType: "NIF", documentNumber: "1",
      discount: "3.00", isMember: false, birthday: "2000-02-02", gender: "OTRO", commercialConsent: true,
      preferredCommercialChannelId: "channel-1", active: true, address: { city: "Arrecife", country: "ES" }
    }, false)).toMatchObject({
      name: "Ana", discount: "3.00", birthday: "2000-02-02", gender: "OTRO", commercialConsent: true,
      preferredCommercialChannelId: "channel-1", city: "Arrecife"
    });
  });

  it("requires a valid country and a channel when consent is enabled", () => {
    expect(validatePartyForm({ ...emptyPartyForm, name: "Ana", documentNumber: "1", country: "E", commercialConsent: true }, false))
      .toEqual(["country", "preferredCommercialChannelId"]);
  });

  it("offers the selected customer receivables action only for customer directories with read permission", () => {
    expect(customerReceivablesActionVisible("customers", true, ["CUSTOMER_RECEIVABLES_READ"])).toBe(true);
    expect(customerReceivablesActionVisible("members", true, ["ADMIN"])).toBe(true);
    expect(customerReceivablesActionVisible("suppliers", true, ["ADMIN"])).toBe(false);
    expect(customerReceivablesActionVisible("customers", false, ["ADMIN"])).toBe(false);
  });
});
