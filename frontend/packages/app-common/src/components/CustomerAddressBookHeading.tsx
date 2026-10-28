import { ErpSelect } from "./ErpSelect";
import type { CustomerContactDraft } from "./customerContactAddresses";

export function CustomerAddressBookHeading({ addresses, selectedId, disabled, onSelect, onAdd, t }: {
  addresses: CustomerContactDraft[]; selectedId: string; disabled: boolean;
  onSelect: (id: string) => void; onAdd: () => void; t: (key: string) => string;
}) {
  const title = t("party.gestion.section.contact");
  return <div className="party-address-book-heading">
    <ErpSelect aria-label={title} value={selectedId} disabled={disabled} maxPopoverWidth={600}
      onChange={value => value === "add-address" ? onAdd() : onSelect(value)}
      options={[
        ...addresses.map((entry, index) => ({ value: entry.id, label: [
          index === 0 ? t("party.customerEditor.address.default") : "",
          entry.address.trim() || t("party.customerEditor.address.unnamed").replace("{number}", String(index + 1)),
          entry.phone.trim(),
        ].filter(Boolean).join(" · ") })),
        { value: "add-address", label: t("party.customerEditor.address.add"), disabled: addresses.length >= 50 },
      ]} />
  </div>;
}
