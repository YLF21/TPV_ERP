import { useEffect, useRef, useState } from "react";
import { apiProblemCode, apiRequest } from "../api/client";
import type { UserSession } from "../types";
import type { CustomerView, PartyForm } from "./PartyDirectoryPanel";
import type { PartyContactField } from "./PartyFormFields";
import { customerContactDrafts, customerContactRequests, newCustomerContact, type CustomerContactDraft } from "./customerContactAddresses";

export function customerAddressError(failure: unknown): string {
  switch (apiProblemCode(failure)) {
    case "CUSTOMER_ADDRESSES_VERSION_CONFLICT": return "party.customerEditor.address.stale";
    case "CUSTOMER_ADDRESSES_PENDING": return "party.customerEditor.address.pending";
    case "CUSTOMER_ADDRESSES_CONFLICT": return "party.customerEditor.address.conflict";
    case "CUSTOMER_ADDRESSES_INVALID": return "party.customerEditor.address.invalid";
    case "CUSTOMER_ADDRESSES_REFRESH_REQUIRED":
    case "CUSTOMER_ADDRESSES_SAAS_UNAVAILABLE": return "party.customerEditor.address.unavailable";
    default: return "party.customerEditor.address.unavailable";
  }
}

export function useCustomerAddressBookEditor(session: UserSession, onLoaded: (customer: CustomerView, resetForm: boolean) => void) {
  const [drafts, setDrafts] = useState<CustomerContactDraft[]>([]);
  const [initial, setInitial] = useState<CustomerContactDraft[]>([]);
  const [selectedId, setSelectedId] = useState("");
  const [source, setSource] = useState<CustomerView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const request = useRef<AbortController | null>(null);
  const loadedCallback = useRef(onLoaded);
  loadedCallback.current = onLoaded;
  useEffect(() => () => { request.current?.abort(); }, [session.accessToken]);

  function cancel() { request.current?.abort(); request.current = null; setBusy(false); }
  function install(customer: CustomerView, fallback: PartyForm) {
    const next = customerContactDrafts(customer, fallback);
    setSource(customer); setDrafts(next); setInitial(next); setSelectedId(next[0].id);
  }
  function openNew(form: PartyForm) {
    cancel(); setError(""); setSource(null);
    const next = [newCustomerContact(form)];
    setDrafts(next); setInitial(next); setSelectedId(next[0].id);
  }
  async function refresh(customer: CustomerView, fallback: PartyForm, resetForm: boolean, useCentral = false) {
    cancel(); setBusy(true); setError("");
    const controller = new AbortController(); request.current = controller;
    try {
      const result = await apiRequest<CustomerView>(`/customers/${encodeURIComponent(customer.id)}/addresses/${useCentral ? "use-central" : "refresh"}`, {
        method: "POST", token: session.accessToken, signal: controller.signal,
        ...(useCentral ? { body: { expectedVersion: customer.version } } : {}),
      });
      if (controller.signal.aborted) return;
      if (!result || result.id !== customer.id || !Array.isArray(result.addresses) || result.addresses.length === 0) throw new Error("invalid_address_book_response");
      install(result, fallback); loadedCallback.current(result, resetForm);
    } catch (failure) {
      if (!controller.signal.aborted) setError(customerAddressError(failure));
    } finally {
      if (request.current === controller) { request.current = null; setBusy(false); }
    }
  }
  function openExisting(customer: CustomerView, form: PartyForm) {
    cancel(); setError(""); install(customer, form);
    void refresh(customer, form, true);
  }
  function change(field: PartyContactField, value: string) {
    setDrafts(current => current.map(entry => entry.id === selectedId ? { ...entry, [field]: value } : entry));
  }
  function applyAddress(address: Pick<CustomerContactDraft, "address" | "postalCode" | "city" | "province" | "country">) {
    if (busy) return;
    setDrafts(current => current.map(entry => entry.id === selectedId ? { ...entry, ...address } : entry));
  }
  function add() {
    if (drafts.length >= 50) { setError("party.customerEditor.address.limit"); return; }
    const next = newCustomerContact();
    setDrafts(current => [...current, next]); setSelectedId(next.id); setError("");
  }
  function makeDefault() {
    setDrafts(current => {
      const selected = current.find(entry => entry.id === selectedId);
      return selected ? [selected, ...current.filter(entry => entry.id !== selectedId)] : current;
    });
  }
  function removeSelected() {
    if (busy || !drafts.some((entry, index) => index > 0 && entry.id === selectedId)) return;
    setDrafts(current => current.filter((entry, index) => index === 0 || entry.id !== selectedId));
    setSelectedId(drafts[0].id); setError("");
  }
  return {
    drafts, source, selectedId, selected: drafts.find(entry => entry.id === selectedId) ?? drafts[0],
    busy, error, status: source?.addressSyncStatus,
    dirty: JSON.stringify(customerContactRequests(drafts)) !== JSON.stringify(customerContactRequests(initial)),
    select: setSelectedId, change, applyAddress, add, makeDefault, removeSelected, openNew, openExisting, cancel,
    reload: (form: PartyForm, useCentral = false) => source ? refresh(source, form, false, useCentral) : Promise.resolve(),
  };
}
