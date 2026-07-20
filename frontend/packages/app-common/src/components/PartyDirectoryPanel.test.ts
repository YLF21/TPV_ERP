import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { UserSession } from "../types";
import {
  availableMemberCustomers,
  buildPartyRequest,
  customerReceivablesActionVisible,
  emptyPartyForm,
  filterPartyDirectoryEntries,
  memberActivationPath,
  partyDirectoryColumnDefinitions,
  PartyDirectoryPanel,
  partyFormFromView,
  validatePartyForm
} from "./PartyDirectoryPanel";
const layoutSession: UserSession = {
  username: "layout.user",
  displayName: "Layout User",
  permissions: ["CUSTOMERS_READ"]
};

describe("PartyDirectoryPanel", () => {
  it("builds the complete customer request", () => {
    expect(buildPartyRequest({
      ...emptyPartyForm, name: " Cliente Uno ", documentType: "NIF", documentNumber: " 123A ",
      birthday: "1990-05-12", gender: "FEMENINO", discount: "7.5", commercialConsent: true,
      preferredCommercialChannelId: "channel-1", country: "es", creditEnabled: true,
      creditLimit: "2500.50", paymentTermDays: "45", creditBlocked: false, blockOnOverdue: true
    }, false)).toMatchObject({
      fiscalName: "Cliente Uno", documentNumber: "123A", birthday: "1990-05-12", gender: "FEMENINO",
      discount: 7.5, commercialConsent: true, preferredCommercialChannelId: "channel-1",
      address: { country: "ES" }, isMember: false, creditEnabled: true, creditLimit: 2500.5,
      unlimitedCredit: false, paymentTermDays: 45, creditBlocked: false, blockOnOverdue: true
    });
  });

  it("builds only fiscal and contact data for suppliers", () => {
    const request = buildPartyRequest({
      ...emptyPartyForm, name: "Proveedor SL", tradeName: "Proveedor", documentNumber: "B123",
      birthday: "1990-01-01", commercialConsent: true
    }, true);
    expect(request).toEqual({
      legalName: "Proveedor SL", tradeName: "Proveedor", documentType: "NIF", documentNumber: "B123",
      address: { address: null, postalCode: null, city: null, province: null, country: "ES" },
      phone: null, email: null, notes: null
    });
  });

  it("preserves an existing membership when the customer identity is edited", () => {
    expect(buildPartyRequest({
      ...emptyPartyForm, name: "Cliente socio", documentNumber: "1", numMember: "EXT-7"
    }, false, true)).toMatchObject({ isMember: true, numMember: "EXT-7" });
  });

  it("restores persisted customer fields for editing", () => {
    expect(partyFormFromView({
      id: "c1", clientId: "C-01-1", fiscalName: "Ana", documentType: "NIF", documentNumber: "1",
      discount: "3.00", isMember: false, birthday: "2000-02-02", gender: "OTRO", commercialConsent: true,
      preferredCommercialChannelId: "channel-1", active: true, address: { city: "Arrecife", country: "ES" },
      creditEnabled: false, creditLimit: "500.00", paymentTermDays: 15, creditBlocked: true, blockOnOverdue: true
    }, false)).toMatchObject({
      name: "Ana", discount: "3.00", birthday: "2000-02-02", gender: "OTRO", commercialConsent: true,
      preferredCommercialChannelId: "channel-1", city: "Arrecife", creditEnabled: false,
      creditLimit: "500.00", paymentTermDays: "15", creditBlocked: true, blockOnOverdue: true
    });
  });

  it("requires a valid country and a channel when consent is enabled", () => {
    expect(validatePartyForm({ ...emptyPartyForm, name: "Ana", documentNumber: "1", country: "E", commercialConsent: true }, false))
      .toEqual(["country", "preferredCommercialChannelId"]);
  });

  it("explicitly clears an existing credit limit when the field is empty", () => {
    expect(buildPartyRequest({
      ...emptyPartyForm, name: "Cliente sin límite", documentNumber: "1", creditLimit: ""
    }, false)).toMatchObject({ creditLimit: null, unlimitedCredit: true });
  });

  it("validates customer credit limits and payment terms", () => {
    expect(validatePartyForm({
      ...emptyPartyForm, name: "Ana", documentNumber: "1", creditLimit: "-0.01", paymentTermDays: "2.5"
    }, false)).toEqual(["creditLimit", "paymentTermDays"]);
    expect(validatePartyForm({
      ...emptyPartyForm, name: "Ana", documentNumber: "1", creditLimit: "", paymentTermDays: "0"
    }, false)).toEqual([]);
  });

  it("offers the selected customer receivables action only for customer directories with read permission", () => {
    expect(customerReceivablesActionVisible("customers", true, ["CUSTOMER_RECEIVABLES_READ"])).toBe(true);
    expect(customerReceivablesActionVisible("members", true, ["ADMIN"])).toBe(true);
    expect(customerReceivablesActionVisible("suppliers", true, ["ADMIN"])).toBe(false);
    expect(customerReceivablesActionVisible("customers", false, ["ADMIN"])).toBe(false);
  });

  it("defines every party table and keeps existing callers compatible", () => {
    expect(partyDirectoryColumnDefinitions("customers").map((column) => column.key))
      .toEqual(["code", "name", "document", "phone", "email", "location", "status"]);
    expect(partyDirectoryColumnDefinitions("members").map((column) => column.key))
      .toEqual(["code", "name", "document", "phone", "email", "balance", "status"]);
    expect(partyDirectoryColumnDefinitions("suppliers").map((column) => column.key))
      .toEqual(["code", "name", "document", "phone", "email", "location", "status"]);

    const html = renderToStaticMarkup(createElement(PartyDirectoryPanel, {
      kind: "customers",
      locale: "es",
      session: layoutSession
    }));
    expect(html.match(/data-column-key=/g)).toHaveLength(7);
    expect(html.match(/draggable="true"/g)).toHaveLength(7);
    expect(html.match(/aria-keyshortcuts="Control\+ArrowLeft Control\+ArrowRight"/g)).toHaveLength(7);
    expect(html.match(/table-layout-column-resizer/g)).toHaveLength(7);
  });

  it("searches every directory from one query and keeps only the status filter", () => {
    const members = [
      {
        id: "m1", customerId: "c1", memberId: "M-001", numMember: "VIP-7", memberSince: "2026-07-01",
        balance: 0, points: 0, categoryName: "Oro", active: true, customerActive: true,
        clientId: "C-001", fiscalName: "Ana", documentType: "NIF", documentNumber: "1"
      },
      {
        id: "m2", customerId: "c2", memberId: "M-002", memberSince: "2026-07-02",
        balance: 0, points: 0, active: false, customerActive: true,
        clientId: "C-002", fiscalName: "Luis", documentType: "NIF", documentNumber: "2"
      }
    ];

    expect(filterPartyDirectoryEntries(members, "members", "oro", "all", "es").map((entry) => entry.id))
      .toEqual(["m1"]);
    expect(filterPartyDirectoryEntries(members, "members", "", "inactive", "es").map((entry) => entry.id))
      .toEqual(["m2"]);

    const html = renderToStaticMarkup(createElement(PartyDirectoryPanel, {
      kind: "customers",
      locale: "es",
      session: layoutSession
    }));
    expect(html.match(/class="erp-select"/g)).toHaveLength(1);
  });

  it("offers only active customers that are not active members", () => {
    const base = {
      clientId: "C-1", fiscalName: "Cliente", documentType: "NIF", documentNumber: "1",
      discount: 0, active: true, address: null
    };
    const customers = [
      { ...base, id: "new", isMember: false, memberUuid: null },
      { ...base, id: "reactivate", isMember: false, memberUuid: "member-old" },
      { ...base, id: "active-member", isMember: true, memberUuid: "member-active" },
      { ...base, id: "inactive-customer", isMember: false, memberUuid: null, active: false }
    ];

    expect(availableMemberCustomers(customers, "", "es").map((customer) => customer.id))
      .toEqual(["new", "reactivate"]);
    expect(memberActivationPath("customer-1")).toBe("/customers/customer-1/member/activate");
    expect(memberActivationPath("customer-1", "deactivate")).toBe("/customers/customer-1/member/deactivate");
  });
});
