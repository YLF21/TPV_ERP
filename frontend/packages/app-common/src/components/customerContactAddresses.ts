import type { CustomerView, PartyForm } from "./PartyDirectoryPanel";
import type { PartyContactForm } from "./PartyFormFields";

export type CustomerContactAddress = {
  id: string;
  address: { address?: string | null; postalCode?: string | null; city?: string | null; province?: string | null; country?: string | null };
  phone?: string | null; email?: string | null; notes?: string | null;
};
export type CustomerAddressSyncStatus = "LOCAL" | "SYNCED" | "PENDING" | "CONFLICT";
export type CustomerContactDraft = PartyContactForm & { id: string };

export function newCustomerContact(source?: Partial<PartyContactForm>, id: string = crypto.randomUUID()): CustomerContactDraft {
  return { id, phone: source?.phone ?? "", email: source?.email ?? "", address: source?.address ?? "", postalCode: source?.postalCode ?? "", city: source?.city ?? "", province: source?.province ?? "", country: source?.country ?? "ES", notes: source?.notes ?? "" };
}
export function customerContactDrafts(customer: CustomerView, fallback: PartyForm): CustomerContactDraft[] {
  return customer.addresses?.length ? customer.addresses.map(entry => newCustomerContact({
    phone: entry.phone ?? "", email: entry.email ?? "", notes: entry.notes ?? "", address: entry.address?.address ?? "",
    postalCode: entry.address?.postalCode ?? "", city: entry.address?.city ?? "", province: entry.address?.province ?? "", country: entry.address?.country ?? "",
  }, entry.id)) : [newCustomerContact(fallback)];
}
export function firstInvalidCustomerContact(drafts: readonly CustomerContactDraft[]): { index: number; field: keyof PartyContactForm } | null {
  const limits = { address: 255, postalCode: 16, city: 128, province: 128, phone: 64, email: 320, notes: 65536 } as const;
  for (const [index, entry] of drafts.entries()) {
    for (const [field, limit] of Object.entries(limits)) {
      if (entry[field as keyof typeof limits].trim().length > limit) return { index, field: field as keyof PartyContactForm };
    }
    const country = entry.country.trim();
    if (country !== "" && !/^[A-Za-z]{2}$/.test(country)) return { index, field: "country" };
    if (entry.email.trim()) {
      const email = document.createElement("input"); email.type = "email"; email.value = entry.email.trim();
      if (!email.checkValidity()) return { index, field: "email" };
    }
  }
  return null;
}
export function customerContactRequests(drafts: readonly CustomerContactDraft[]): CustomerContactAddress[] {
  return drafts.map(entry => ({
    id: entry.id,
    address: { address: entry.address.trim() || null, postalCode: entry.postalCode.trim() || null, city: entry.city.trim() || null, province: entry.province.trim() || null, country: entry.country.trim().toUpperCase() || null },
    phone: entry.phone.trim() || null, email: entry.email.trim() || null, notes: entry.notes.trim() || null,
  }));
}
export function formWithDefaultContact(form: PartyForm, drafts: readonly CustomerContactDraft[]): PartyForm {
  const primary = drafts[0];
  return primary ? { ...form, phone: primary.phone, email: primary.email, address: primary.address, postalCode: primary.postalCode, city: primary.city, province: primary.province, country: primary.country, notes: primary.notes } : form;
}
