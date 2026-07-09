import { useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import { apiRequest } from "../api/client";
import { apiBaseUrl } from "../api/runtime";
import type { LocaleCode } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import deleteImageIcon from "../assets/product/delete.png";

type ProductCreateDialogProps = {
  open: boolean;
  locale: LocaleCode;
  token?: string;
  onClose: () => void;
  onCreated?: (product: ProductCreateResponse) => void;
};

export type ProductTypeCode = "UNIT" | "SERVICE" | "WEIGHT";
export type DiscountTypeCode = "NONE" | "NORMAL" | "MEMBER_PRICE" | "DISCOUNT_PRICE";
export type PriceUseModeCode = "NORMAL" | "MEMBER_PRICE" | "OFFER_PRICE" | "OFFER_DISCOUNT";

export type ProductCreateFormState = {
  familyId: string;
  subfamilyId: string;
  taxId: string;
  productType: ProductTypeCode;
  priceUseMode: PriceUseModeCode;
  discountType: DiscountTypeCode;
  name: string;
  description: string;
  comments: string;
  purchasePrice: string;
  taxesIncluded: boolean;
  code: string;
  barcode: string;
  salePrice: string;
  memberPrice: string;
  wholesalePrice: string;
  offerPrice: string;
  offerDiscountPercent: string;
  offerActive: boolean;
  offerFrom: string;
  offerUntil: string;
};

export type ProductCreateFieldName =
  | "familyId"
  | "subfamilyId"
  | "taxId"
  | "productType"
  | "priceUseMode"
  | "discountType"
  | "name"
  | "description"
  | "comments"
  | "purchasePrice"
  | "taxesIncluded"
  | "code"
  | "barcode"
  | "salePrice"
  | "memberPrice"
  | "wholesalePrice"
  | "offerPrice"
  | "offerDiscountPercent"
  | "offerActive"
  | "offerRange";

type FamilyView = {
  id: string;
  name?: string | null;
  defaultFamily?: boolean | null;
};

type SubfamilyView = {
  id: string;
  name?: string | null;
  familyId?: string | null;
};

type TaxView = {
  id: string;
  percentage?: number | string | null;
  defaultTax?: boolean | null;
};

type ProductSelectOption = {
  value: string;
  label: string;
};

export type ProductCreateResponse = {
  id: string;
  code?: string | null;
  name?: string | null;
};

type SaveProductOptions = {
  form: ProductCreateFormState;
  token: string;
  imageFile: File | null;
  createProduct?: (body: ReturnType<typeof buildCreateProductRequest>, token: string) => Promise<ProductCreateResponse>;
  uploadImage?: (productId: string, file: File, token: string) => Promise<void>;
};

export type SaveProductResult = {
  product: ProductCreateResponse;
  imageUploadFailed: boolean;
};

type ProductCreateKeyAction = "close" | "saveContinue" | "saveClose";

const productTypeOptions: ProductTypeCode[] = ["UNIT", "WEIGHT", "SERVICE"];
export const productDiscountTypeOptions: PriceUseModeCode[] = ["NORMAL", "MEMBER_PRICE", "OFFER_PRICE", "OFFER_DISCOUNT"];

export function productCreateKeyAction(key: string): ProductCreateKeyAction | null {
  if (key === "Escape") {
    return "close";
  }
  if (key === "F8") {
    return "saveContinue";
  }
  if (key === "F9") {
    return "saveClose";
  }
  return null;
}

export function productImageUploadPath(productId: string) {
  return `/products/${encodeURIComponent(productId)}/image`;
}

export function createDefaultProductForm(): ProductCreateFormState {
  return {
    familyId: "",
    subfamilyId: "",
    taxId: "",
    productType: "UNIT",
    priceUseMode: "NORMAL",
    discountType: "NORMAL",
    name: "",
    description: "",
    comments: "",
    purchasePrice: "0.00",
    taxesIncluded: true,
    code: "",
    barcode: "",
    salePrice: "0.00",
    memberPrice: "",
    wholesalePrice: "",
    offerPrice: "",
    offerDiscountPercent: "",
    offerActive: false,
    offerFrom: "",
    offerUntil: ""
  };
}

export function buildCreateProductRequest(form: ProductCreateFormState) {
  const noDiscountLocked = isNoDiscountLocked(form);
  const priceUseMode = noDiscountLocked ? "NORMAL" : normalizePriceUseMode(form);
  const offerActive = isOfferPriceUseMode(priceUseMode);
  const offerPrice = priceUseMode === "OFFER_DISCOUNT"
    ? priceFromOfferDiscount(form.salePrice, form.offerDiscountPercent) || form.offerPrice
    : form.offerPrice;
  return {
    familyId: form.familyId,
    subfamilyId: emptyToNull(form.subfamilyId),
    taxId: form.taxId,
    productType: form.productType,
    priceUseMode,
    discountType: noDiscountLocked ? "NONE" : discountTypeFromPriceUseMode(priceUseMode),
    name: form.name.trim(),
    description: emptyToNull(form.description),
    comments: emptyToNull(form.comments),
    purchasePrice: decimalOrZero(form.purchasePrice),
    taxesIncluded: form.taxesIncluded,
    code: form.code.trim(),
    barcode: emptyToNull(form.barcode),
    salePrice: decimalOrZero(form.salePrice),
    memberPrice: emptyToNull(form.memberPrice),
    wholesalePrice: emptyToNull(form.wholesalePrice),
    offerPrice: noDiscountLocked ? null : emptyToNull(offerPrice),
    offerDiscountPercent: noDiscountLocked ? null : emptyToNull(form.offerDiscountPercent),
    offerActive,
    offerFrom: emptyToNull(form.offerFrom),
    offerUntil: emptyToNull(form.offerUntil)
  };
}

function emptyToNull(value: string) {
  const trimmed = value.trim();
  return trimmed.length === 0 ? null : trimmed;
}

function decimalOrZero(value: string) {
  const trimmed = value.trim().replace(",", ".");
  return trimmed.length === 0 ? "0.00" : trimmed;
}

function normalizePriceUseMode(form: ProductCreateFormState): PriceUseModeCode {
  if (form.priceUseMode) {
    return form.priceUseMode;
  }
  if (form.discountType === "MEMBER_PRICE") {
    return "MEMBER_PRICE";
  }
  if (form.discountType === "DISCOUNT_PRICE") {
    return form.offerDiscountPercent.trim() ? "OFFER_DISCOUNT" : "OFFER_PRICE";
  }
  return "NORMAL";
}

function discountTypeFromPriceUseMode(mode: PriceUseModeCode): DiscountTypeCode {
  if (mode === "MEMBER_PRICE") {
    return "MEMBER_PRICE";
  }
  if (mode === "OFFER_PRICE" || mode === "OFFER_DISCOUNT") {
    return "DISCOUNT_PRICE";
  }
  return "NORMAL";
}

function isOfferPriceUseMode(mode: PriceUseModeCode) {
  return mode === "OFFER_PRICE" || mode === "OFFER_DISCOUNT";
}

function isNoDiscountLocked(form: ProductCreateFormState) {
  return form.discountType === "NONE";
}

function canSubmitProduct(form: ProductCreateFormState) {
  return productCreateValidationErrors(form).length === 0;
}

export function productCreateValidationErrors(form: ProductCreateFormState) {
  const errors: string[] = [];
  const priceUseMode = normalizePriceUseMode(form);
  const offerActive = isOfferPriceUseMode(priceUseMode);
  const offerPrice = priceUseMode === "OFFER_DISCOUNT"
    ? priceFromOfferDiscount(form.salePrice, form.offerDiscountPercent) || form.offerPrice
    : form.offerPrice;
  if (!form.familyId) {
    errors.push("familyId");
  }
  if (!form.taxId) {
    errors.push("taxId");
  }
  if (!form.name.trim()) {
    errors.push("name");
  }
  if (!form.code.trim()) {
    errors.push("code");
  }
  if (!form.purchasePrice.trim()) {
    errors.push("purchasePrice");
  }
  if (!form.salePrice.trim()) {
    errors.push("salePrice");
  }
  if (offerActive && !offerPrice.trim()) {
    errors.push("offerPrice");
  }
  if (offerActive && !form.offerFrom) {
    errors.push("offerFrom");
  }
  return errors;
}

export function canLeaveProductField(form: ProductCreateFormState, fieldName: string | null | undefined) {
  if (!fieldName) {
    return true;
  }
  if (fieldName === "familyId") {
    return Boolean(form.familyId);
  }
  if (fieldName === "taxId") {
    return Boolean(form.taxId);
  }
  if (fieldName === "productType") {
    return Boolean(form.productType);
  }
  const priceUseMode = normalizePriceUseMode(form);
  const offerActive = isOfferPriceUseMode(priceUseMode);
  if (fieldName === "priceUseMode" || fieldName === "discountType") {
    return Boolean(priceUseMode);
  }
  if (fieldName === "name") {
    return Boolean(form.name.trim());
  }
  if (fieldName === "code") {
    return Boolean(form.code.trim());
  }
  if (fieldName === "purchasePrice") {
    return Boolean(form.purchasePrice.trim());
  }
  if (fieldName === "salePrice") {
    return Boolean(form.salePrice.trim());
  }
  if (fieldName === "offerPrice") {
    return !offerActive || Boolean(form.offerPrice.trim()) || (priceUseMode === "OFFER_DISCOUNT" && Boolean(priceFromOfferDiscount(form.salePrice, form.offerDiscountPercent)));
  }
  if (fieldName === "offerRange") {
    return !offerActive || Boolean(form.offerFrom);
  }
  return true;
}

export function nextProductFieldIndex(currentIndex: number, fieldCount: number, backwards: boolean) {
  if (fieldCount <= 0) {
    return -1;
  }
  return backwards
    ? (currentIndex - 1 + fieldCount) % fieldCount
    : (currentIndex + 1) % fieldCount;
}

function priceFromOfferDiscount(salePrice: string, discountPercent: string) {
  const sale = Number(salePrice.trim().replace(",", "."));
  const discount = Number(discountPercent.trim().replace(",", "."));
  if (!Number.isFinite(sale) || !Number.isFinite(discount)) {
    return "";
  }
  return Math.max(0, sale - (sale * discount / 100)).toFixed(2);
}

function toIsoDate(value: Date) {
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, "0");
  const day = String(value.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function parseIsoDate(value: string) {
  const [year, month, day] = value.split("-").map(Number);
  if (!year || !month || !day) {
    return null;
  }
  return new Date(year, month - 1, day);
}

function startOfMonth(value: Date) {
  return new Date(value.getFullYear(), value.getMonth(), 1);
}

function buildCalendarDays(month: Date) {
  const firstDay = startOfMonth(month);
  const firstWeekday = (firstDay.getDay() + 6) % 7;
  const daysInMonth = new Date(firstDay.getFullYear(), firstDay.getMonth() + 1, 0).getDate();
  return [
    ...Array.from({ length: firstWeekday }, () => null),
    ...Array.from({ length: daysInMonth }, (_, index) => new Date(firstDay.getFullYear(), firstDay.getMonth(), index + 1))
  ];
}

function normalizeDateRange(dateFrom: string, dateTo: string) {
  if (!dateFrom && !dateTo) {
    return { dateFrom: "", dateTo: "" };
  }
  const start = dateFrom || dateTo;
  const end = dateTo || dateFrom;
  return start <= end ? { dateFrom: start, dateTo: end } : { dateFrom: end, dateTo: start };
}

function formatShortDate(value: string) {
  const [year, month, day] = value.split("-");
  return year && month && day ? `${day}/${month}/${year}` : value;
}

function formatOfferRange(from: string, to: string) {
  if (!from && !to) {
    return "";
  }
  if (!to || from === to) {
    return formatShortDate(from || to);
  }
  return `${formatShortDate(from)}-${formatShortDate(to)}`;
}

async function uploadProductImage(productId: string, file: File, token: string) {
  const body = new FormData();
  body.append("file", file);
  const response = await fetch(`${apiBaseUrl}${productImageUploadPath(productId)}`, {
    method: "PUT",
    headers: {
      Authorization: `Bearer ${token}`
    },
    body
  });
  if (!response.ok) {
    let message = response.statusText || "image_upload_error";
    try {
      const problem = await response.json() as { detail?: string; code?: string };
      message = problem.detail || problem.code || message;
    } catch {
      // Keep HTTP status text when the backend does not return a problem body.
    }
    throw new Error(message);
  }
}

async function createProductRequest(body: ReturnType<typeof buildCreateProductRequest>, token: string) {
  return apiRequest<ProductCreateResponse>("/products", {
    method: "POST",
    token,
    body
  });
}

export async function saveProductWithOptionalImage({
  form,
  token,
  imageFile,
  createProduct = createProductRequest,
  uploadImage = uploadProductImage
}: SaveProductOptions): Promise<SaveProductResult> {
  const product = await createProduct(buildCreateProductRequest(form), token);
  if (!imageFile) {
    return { product, imageUploadFailed: false };
  }
  try {
    await uploadImage(product.id, imageFile, token);
    return { product, imageUploadFailed: false };
  } catch {
    return { product, imageUploadFailed: true };
  }
}

export function productCreateErrorMessage(error: unknown, fallback: string) {
  if (error instanceof TypeError || (error instanceof Error && error.message === "Failed to write request")) {
    return fallback;
  }
  return error instanceof Error ? error.message : fallback;
}

function taxLabel(tax: TaxView) {
  return `${tax.percentage ?? "0"}%`;
}

function productTypeLabel(type: ProductTypeCode) {
  if (type === "WEIGHT") {
    return "product.type.weight";
  }
  if (type === "SERVICE") {
    return "product.type.service";
  }
  return "product.type.unit";
}

function discountTypeLabel(type: PriceUseModeCode) {
  if (type === "NORMAL") {
    return "product.discount.salePrice";
  }
  if (type === "MEMBER_PRICE") {
    return "product.discount.memberPrice";
  }
  if (type === "OFFER_PRICE") {
    return "product.discount.offerPrice";
  }
  return "product.discount.offerDiscount";
}

export function ProductCreateDialog({
  open,
  locale,
  token,
  onClose,
  onCreated
}: ProductCreateDialogProps) {
  const t = createTranslator(locale);
  const [form, setForm] = useState<ProductCreateFormState>(() => createDefaultProductForm());
  const [families, setFamilies] = useState<FamilyView[]>([]);
  const [subfamilies, setSubfamilies] = useState<SubfamilyView[]>([]);
  const [taxes, setTaxes] = useState<TaxView[]>([]);
  const [status, setStatus] = useState("");
  const [saving, setSaving] = useState(false);
  const [openDropdown, setOpenDropdown] = useState("");
  const [familyPickerOpen, setFamilyPickerOpen] = useState(false);
  const [selectedProductFamily, setSelectedProductFamily] = useState({ familyId: "", subfamilyId: "" });
  const [offerPickerOpen, setOfferPickerOpen] = useState(false);
  const [offerRangeStart, setOfferRangeStart] = useState<string | null>(null);
  const [calendarMonth, setCalendarMonth] = useState(() => startOfMonth(new Date()));
  const [imageFile, setImageFile] = useState<File | null>(null);
  const [imagePreview, setImagePreview] = useState("");
  const formRef = useRef<HTMLDivElement | null>(null);
  const selectedFamily = useMemo(() => families.find((family) => family.id === form.familyId), [families, form.familyId]);
  const selectedSubfamily = useMemo(() => subfamilies.find((subfamily) => subfamily.id === form.subfamilyId), [subfamilies, form.subfamilyId]);
  const selectedTax = useMemo(() => taxes.find((tax) => tax.id === form.taxId), [taxes, form.taxId]);
  const selectedPriceUseMode = normalizePriceUseMode(form);
  const offerActive = isOfferPriceUseMode(selectedPriceUseMode);
  const calendarTitle = new Intl.DateTimeFormat(locale === "zh" ? "zh-CN" : locale === "en" ? "en-GB" : "es-ES", { month: "long", year: "numeric" }).format(calendarMonth);

  function resetDialogState() {
    setForm(createDefaultProductForm());
    setStatus("");
    setSaving(false);
    setOpenDropdown("");
    setFamilyPickerOpen(false);
    setSelectedProductFamily({ familyId: "", subfamilyId: "" });
    setOfferPickerOpen(false);
    setOfferRangeStart(null);
    setCalendarMonth(startOfMonth(new Date()));
    changeImage(null);
  }

  function closeProductDialog() {
    resetDialogState();
    onClose();
  }

  useEffect(() => {
    if (!open || !token) {
      return;
    }

    let cancelled = false;
    async function loadCatalog() {
      setStatus("");
      try {
        const [nextFamilies, nextTaxes] = await Promise.all([
          apiRequest<FamilyView[]>("/families", { token }),
          apiRequest<TaxView[]>("/taxes/selectable", { token })
        ]);
        const nextSubfamilies = (await Promise.all(nextFamilies.map(async (family) => {
          try {
            return await apiRequest<SubfamilyView[]>(`/families/${encodeURIComponent(family.id)}/subfamilies`, { token });
          } catch {
            return [] as SubfamilyView[];
          }
        }))).flat();
        if (cancelled) {
          return;
        }
        setFamilies(nextFamilies);
        setSubfamilies(nextSubfamilies);
        setTaxes(nextTaxes);
        setForm((current) => ({
          ...current,
          familyId: current.familyId || nextFamilies.find((family) => family.defaultFamily)?.id || nextFamilies[0]?.id || "",
          taxId: current.taxId || nextTaxes.find((tax) => tax.defaultTax)?.id || nextTaxes[0]?.id || ""
        }));
      } catch (error) {
        if (!cancelled) {
          setStatus(productCreateErrorMessage(error, t("product.create.loadError")));
        }
      }
    }

    void loadCatalog();
    return () => {
      cancelled = true;
    };
  }, [open, token, locale]);

  useEffect(() => {
    if (!open) {
      return;
    }

    function handleProductShortcut(event: globalThis.KeyboardEvent) {
      const action = productCreateKeyAction(event.key);
      if (!action) {
        return;
      }
      event.preventDefault();
      if (action === "close") {
        closeProductDialog();
        return;
      }
      void submitProduct(action === "saveClose");
    }

    window.addEventListener("keydown", handleProductShortcut);
    return () => window.removeEventListener("keydown", handleProductShortcut);
  }, [open, form, token, imageFile, saving]);

  if (!open) {
    return null;
  }

  function updateField<K extends keyof ProductCreateFormState>(key: K, value: ProductCreateFormState[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  function updatePriceUseMode(mode: PriceUseModeCode) {
    setForm((current) => ({
      ...current,
      priceUseMode: mode,
      discountType: discountTypeFromPriceUseMode(mode),
      offerActive: isOfferPriceUseMode(mode)
    }));
  }

  function toggleNoDiscountLock() {
    setForm((current) => {
      const locked = !isNoDiscountLocked(current);
      return {
        ...current,
        priceUseMode: "NORMAL",
        discountType: locked ? "NONE" : "NORMAL",
        offerActive: false
      };
    });
  }

  function updateOfferDiscountPercent(value: string) {
    setForm((current) => {
      const nextOfferPrice = priceFromOfferDiscount(current.salePrice, value);
      return {
        ...current,
        priceUseMode: "OFFER_DISCOUNT",
        discountType: "DISCOUNT_PRICE",
        offerActive: true,
        offerDiscountPercent: value,
        offerPrice: nextOfferPrice || current.offerPrice
      };
    });
  }

  function focusProductField(event: KeyboardEvent<HTMLElement>) {
    if (event.key !== "Enter" || event.altKey || event.ctrlKey || event.metaKey) {
      return;
    }
    const target = event.target as HTMLElement;
    if (target.closest(".filter-popover") || target.closest(".date-popover")) {
      return;
    }
    const fieldName = target.closest<HTMLElement>("[data-product-field-name]")?.dataset.productFieldName;
    if (!canLeaveProductField(form, fieldName)) {
      event.preventDefault();
      setStatus(t("product.create.required"));
      target.focus();
      return;
    }
    setStatus("");
    const fields = Array.from(formRef.current?.querySelectorAll<HTMLElement>("[data-product-field]:not(:disabled)") ?? []);
    const currentIndex = fields.findIndex((field) => field === target || field.contains(target));
    const nextIndex = nextProductFieldIndex(currentIndex, fields.length, event.shiftKey);
    if (nextIndex < 0) {
      return;
    }
    event.preventDefault();
    fields[nextIndex]?.focus();
  }

  function selectFamily(familyId: string, subfamilyId = "") {
    setSelectedProductFamily({ familyId, subfamilyId });
  }

  function applyProductFamilySelection() {
    setForm((current) => ({
      ...current,
      familyId: selectedProductFamily.familyId,
      subfamilyId: selectedProductFamily.subfamilyId
    }));
    setFamilyPickerOpen(false);
    setOpenDropdown("");
  }

  function selectOfferDate(date: Date) {
    const selected = toIsoDate(date);
    setCalendarMonth(startOfMonth(date));
    if (!offerRangeStart) {
      setOfferRangeStart(selected);
      setForm((current) => ({ ...current, offerFrom: selected, offerUntil: selected }));
      return;
    }
    const range = normalizeDateRange(offerRangeStart, selected);
    setForm((current) => ({ ...current, offerFrom: range.dateFrom, offerUntil: range.dateTo }));
    setOfferRangeStart(null);
    setOfferPickerOpen(false);
  }

  function clearOfferEndDate() {
    setForm((current) => ({ ...current, offerUntil: "" }));
    setOfferRangeStart(null);
    setOfferPickerOpen(false);
  }

  function changeImage(file: File | null) {
    setImageFile(file);
    setImagePreview((current) => {
      if (current) {
        URL.revokeObjectURL(current);
      }
      return file ? URL.createObjectURL(file) : "";
    });
  }

  function renderDropdown(
    name: string,
    label: string,
    value: string,
    options: ProductSelectOption[],
    onChange: (value: string) => void,
    placeholder = t("product.create.select")
  ) {
    const isOpen = openDropdown === name;
    const fieldName = name === "type" ? "productType" : name === "tax" ? "taxId" : name === "discount" ? "discountType" : name;
    const selectedLabel = options.find((option) => option.value === value)?.label ?? placeholder;
    return (
      <div className={`filter-field product-dropdown-field ${isOpen ? "open" : ""}`}>
        <span>{label}</span>
        <button
          type="button"
          className="filter-select-button"
          data-product-field
          data-product-field-name={fieldName}
          aria-expanded={isOpen}
          onClick={() => {
            setFamilyPickerOpen(false);
            setOpenDropdown((current) => current === name ? "" : name);
          }}
        >
          <span>{selectedLabel}</span>
          <span className="filter-control-arrow">v</span>
        </button>
        {isOpen && (
          <div className="filter-popover product-select-popover">
            {options.map((option) => (
              <button
                type="button"
                className={option.value === value ? "selected" : ""}
                key={option.value}
                onClick={() => {
                  onChange(option.value);
                  setOpenDropdown("");
                }}
              >
                {option.label}
              </button>
            ))}
          </div>
        )}
      </div>
    );
  }

  function renderPriceUseOptions() {
    return (
      <div className="product-price-use" data-product-field data-product-field-name="priceUseMode">
        <span>{t("product.field.usePrice")}</span>
        <div className="product-price-use-options" role="radiogroup" aria-label={t("product.field.usePrice")}>
          {productDiscountTypeOptions.map((mode) => {
            const selected = selectedPriceUseMode === mode;
            return (
              <button
                type="button"
                className={selected ? "selected" : ""}
                role="radio"
                aria-checked={selected}
                key={mode}
                onClick={() => updatePriceUseMode(mode)}
              >
                {t(discountTypeLabel(mode))}
              </button>
            );
          })}
        </div>
      </div>
    );
  }

  async function submitProduct(closeAfterSave: boolean) {
    if (saving || !token || !canSubmitProduct(form)) {
      setStatus(t("product.create.required"));
      return;
    }
    setSaving(true);
    setStatus("");
    try {
      const result = await saveProductWithOptionalImage({
        form,
        token,
        imageFile
      });
      onCreated?.(result.product);
      resetDialogState();
      if (closeAfterSave) {
        onClose();
      }
    } catch (error) {
      setStatus(productCreateErrorMessage(error, t("product.create.saveError")));
    } finally {
      if (!closeAfterSave) {
        setSaving(false);
      }
    }
  }

  function removeSelectedImage() {
    if (!imageFile) {
      return;
    }
    const confirmed = window.confirm(t("product.image.removeConfirm"));
    if (confirmed) {
      changeImage(null);
    }
  }

  return (
    <div className="filter-overlay product-create-overlay" role="dialog" aria-modal="true" aria-labelledby="product-create-title">
      <section className="filter-dialog product-create-dialog">
        <header className="filter-header">
          <div>
            <h2 id="product-create-title">{t("product.create.title")}</h2>
            <span>{selectedFamily?.name ?? t("product.create.subtitle")}</span>
          </div>
          <button type="button" onClick={closeProductDialog}>{t("common.close")}</button>
        </header>

        <div className="product-create-body">
          <div className="product-create-form" ref={formRef} onKeyDown={focusProductField}>
            <div className="product-create-row product-create-row-two">
              <label>
                <span>{t("stock.column.code")}</span>
                <input data-product-field data-product-field-name="code" required value={form.code} onChange={(event) => updateField("code", event.target.value)} autoFocus />
              </label>
              <label>
                <span>{t("stock.column.barcode")}</span>
                <input data-product-field data-product-field-name="barcode" value={form.barcode} onChange={(event) => updateField("barcode", event.target.value)} />
              </label>
            </div>
            <div className="product-create-row product-create-row-name-type">
              <label>
                <span>{t("product.field.name")}</span>
                <input data-product-field data-product-field-name="name" required value={form.name} onChange={(event) => updateField("name", event.target.value)} />
              </label>
              {renderDropdown(
                "type",
                t("stock.column.type"),
                form.productType,
                productTypeOptions.map((type) => ({ value: type, label: t(productTypeLabel(type)) })),
                (value) => updateField("productType", value as ProductTypeCode)
              )}
            </div>
            <label className="product-create-full">
              <span>{t("product.field.description")}</span>
              <textarea data-product-field data-product-field-name="description" value={form.description} onChange={(event) => updateField("description", event.target.value)} />
            </label>
            <div className="product-create-row product-create-row-family-tax">
              <div className={`filter-field product-family-field ${familyPickerOpen ? "open" : ""}`}>
                <span>{t("stock.column.family")}</span>
                <button
                  type="button"
                  className="filter-select-button"
                  data-product-field
                  data-product-field-name="familyId"
                  aria-expanded={familyPickerOpen}
                  onClick={() => {
                    setOpenDropdown("");
                    setSelectedProductFamily({ familyId: form.familyId, subfamilyId: form.subfamilyId });
                    setFamilyPickerOpen(true);
                  }}
                >
                  <span>{selectedSubfamily?.name ?? selectedFamily?.name ?? t("product.create.select")}</span>
                  <span className="filter-control-arrow">v</span>
                </button>
              </div>
              {renderDropdown(
                "tax",
                t("stock.column.tax"),
                form.taxId,
                taxes.map((tax) => ({ value: tax.id, label: taxLabel(tax) })),
                (value) => updateField("taxId", value)
              )}
              <label className="product-create-check">
                <input data-product-field data-product-field-name="taxesIncluded" type="checkbox" checked={form.taxesIncluded} onChange={(event) => updateField("taxesIncluded", event.target.checked)} />
                <span>{t("product.field.taxesIncluded")}</span>
              </label>
            </div>
            <div className="product-create-row product-create-row-prices">
              <label>
                <span>{t("stock.column.purchasePrice")}</span>
                <input data-product-field data-product-field-name="purchasePrice" required inputMode="decimal" value={form.purchasePrice} onChange={(event) => updateField("purchasePrice", event.target.value)} />
              </label>
              <label>
                <span>{t("stock.column.salePrice")}</span>
                <input data-product-field data-product-field-name="salePrice" required inputMode="decimal" value={form.salePrice} onChange={(event) => updateField("salePrice", event.target.value)} />
              </label>
              <label>
                <span>{t("stock.column.memberPrice")}</span>
                <input data-product-field data-product-field-name="memberPrice" inputMode="decimal" value={form.memberPrice} onChange={(event) => updateField("memberPrice", event.target.value)} />
              </label>
              <label>
                <span>{t("stock.column.wholesalePrice")}</span>
                <input data-product-field data-product-field-name="wholesalePrice" inputMode="decimal" value={form.wholesalePrice} onChange={(event) => updateField("wholesalePrice", event.target.value)} />
              </label>
            </div>
            <div className="product-create-offer-area">
              {renderPriceUseOptions()}
              <label>
                <span>{t("stock.column.offerPrice")}</span>
                <input data-product-field data-product-field-name="offerPrice" inputMode="decimal" value={form.offerPrice} onChange={(event) => updateField("offerPrice", event.target.value)} />
              </label>
              <label>
                <span>{t("product.field.offerDiscountPercent")}</span>
                <input data-product-field data-product-field-name="offerDiscountPercent" inputMode="decimal" value={form.offerDiscountPercent} onChange={(event) => updateOfferDiscountPercent(event.target.value)} />
              </label>
              <div className={`filter-field product-offer-range ${offerPickerOpen ? "open" : ""}`}>
                <span>{t("product.field.offerRange")}</span>
                <div className="date-range-control">
                  <input type="text" value={formatOfferRange(form.offerFrom, form.offerUntil)} readOnly placeholder="01/07/2026-31/07/2026" />
                  <button type="button" data-product-field data-product-field-name="offerRange" aria-expanded={offerPickerOpen} aria-label={t("salesReport.filter.openCalendar")} onClick={() => setOfferPickerOpen((current) => !current)}>
                    <span className="filter-control-arrow">v</span>
                  </button>
                </div>
                {offerPickerOpen && (
                  <div className="date-popover date-range-popover">
                    <p>{offerRangeStart ? t("salesReport.filter.pickDateTo") : t("salesReport.filter.pickDateFrom")}</p>
                    <header className="date-calendar-header">
                      <button type="button" onClick={() => setCalendarMonth((current) => new Date(current.getFullYear(), current.getMonth() - 1, 1))}>{"<"}</button>
                      <strong>{calendarTitle}</strong>
                      <button type="button" onClick={() => setCalendarMonth((current) => new Date(current.getFullYear(), current.getMonth() + 1, 1))}>{">"}</button>
                    </header>
                    <div className="date-calendar-grid">
                      {["L", "M", "X", "J", "V", "S", "D"].map((weekday) => <span className="date-weekday" key={weekday}>{weekday}</span>)}
                      {buildCalendarDays(calendarMonth).map((day, index) => day ? (
                        <button
                          type="button"
                          className={[
                            "date-day",
                            toIsoDate(day) === form.offerFrom || toIsoDate(day) === form.offerUntil ? "selected" : "",
                            toIsoDate(day) > form.offerFrom && toIsoDate(day) < form.offerUntil ? "in-range" : ""
                          ].filter(Boolean).join(" ")}
                          key={toIsoDate(day)}
                          onClick={() => selectOfferDate(day)}
                        >
                          {day.getDate()}
                        </button>
                      ) : <span className="date-day empty" key={`empty-${index}`} />)}
                    </div>
                    <button type="button" className="date-no-end-button" onClick={clearOfferEndDate}>
                      {t("product.field.noEnd")}
                    </button>
                  </div>
                )}
              </div>
              <div className={`product-offer-active-indicator ${offerActive ? "active" : ""}`}>
                <span>{t("product.field.offerActive")}</span>
              </div>
            </div>
            <div className={`product-no-discount-lock ${isNoDiscountLocked(form) ? "active" : ""}`}>
              <button
                type="button"
                className={isNoDiscountLocked(form) ? "active" : ""}
                data-product-field
                data-product-field-name="discountType"
                onClick={toggleNoDiscountLock}
              >
                {t("product.noDiscountLock.button")}
              </button>
              <span>{t("product.noDiscountLock.message")}</span>
            </div>
            <label className="product-create-full">
              <span>{t("product.field.comments")}</span>
              <textarea data-product-field data-product-field-name="comments" value={form.comments} onChange={(event) => updateField("comments", event.target.value)} />
            </label>
          </div>
          <aside className="product-create-media">
            <div className="product-image-preview">
              {imagePreview ? <img alt="" src={imagePreview} /> : <span>{t("product.image.empty")}</span>}
            </div>
            <div className="product-image-actions">
              <label className="product-file-button">
                <span>{t("product.image.browse")}</span>
                <input type="file" accept="image/*" onChange={(event) => changeImage(event.target.files?.[0] ?? null)} />
              </label>
              <button
                type="button"
                className="product-image-delete-button"
                disabled={!imageFile}
                aria-label={t("product.image.remove")}
                onClick={removeSelectedImage}
              >
                <img alt="" src={deleteImageIcon} />
                <span>{t("product.image.remove")}</span>
              </button>
            </div>
          </aside>
        </div>

        {status && <p className="product-create-status">{status}</p>}
        <footer className="filter-actions">
          <button type="button" onClick={closeProductDialog}>{t("common.close")}</button>
          <button type="button" disabled={saving || !canSubmitProduct(form)} onClick={() => void submitProduct(false)}>
            {saving ? t("product.create.saving") : t("product.create.saveContinue")}
          </button>
          <button type="button" disabled={saving || !canSubmitProduct(form)} onClick={() => void submitProduct(true)}>
            {saving ? t("product.create.saving") : t("product.create.saveClose")}
          </button>
        </footer>
      </section>
      {familyPickerOpen && (
        <div className="filter-overlay stock-family-overlay" role="dialog" aria-modal="true" aria-labelledby="product-family-title">
          <section className="filter-dialog stock-family-dialog">
            <header className="filter-header">
              <h2 id="product-family-title">{t("stock.column.family")}</h2>
              <button type="button" onClick={() => setFamilyPickerOpen(false)}>{t("common.close")}</button>
            </header>
            <div className="stock-family-list">
              {families.length === 0 && <p>{t("stock.filter.noFamilies")}</p>}
              {families.map((family) => {
                const familySubfamilies = subfamilies.filter((subfamily) => subfamily.familyId === family.id);
                const selected = selectedProductFamily.familyId === family.id && !selectedProductFamily.subfamilyId;
                return (
                  <div className="stock-family-group" key={family.id}>
                    <div className={`stock-family-row ${selected ? "selected" : ""}`}>
                      <button type="button" className="stock-family-expand" disabled>
                        {familySubfamilies.length > 0 ? "v" : ""}
                      </button>
                      <button
                        type="button"
                        className="stock-family-choice"
                        onClick={() => selectFamily(family.id)}
                        onDoubleClick={() => {
                          setSelectedProductFamily({ familyId: family.id, subfamilyId: "" });
                          setForm((current) => ({ ...current, familyId: family.id, subfamilyId: "" }));
                          setFamilyPickerOpen(false);
                        }}
                      >
                        {family.name}
                      </button>
                    </div>
                    {familySubfamilies.length > 0 && (
                      <div className="stock-subfamily-list">
                        {familySubfamilies.map((subfamily) => {
                          const subfamilySelected = selectedProductFamily.familyId === family.id
                            && selectedProductFamily.subfamilyId === subfamily.id;
                          return (
                            <button
                              type="button"
                              className={subfamilySelected ? "selected" : ""}
                              key={subfamily.id}
                              onClick={() => selectFamily(family.id, subfamily.id)}
                              onDoubleClick={() => {
                                setSelectedProductFamily({ familyId: family.id, subfamilyId: subfamily.id });
                                setForm((current) => ({ ...current, familyId: family.id, subfamilyId: subfamily.id }));
                                setFamilyPickerOpen(false);
                              }}
                            >
                              {subfamily.name}
                            </button>
                          );
                        })}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
            <footer className="filter-actions">
              <button type="button" onClick={() => setSelectedProductFamily({ familyId: "", subfamilyId: "" })}>{t("salesReport.filter.clear")}</button>
              <button type="button" onClick={applyProductFamilySelection}>{t("stock.filter.apply")}</button>
            </footer>
          </section>
        </div>
      )}
    </div>
  );
}
