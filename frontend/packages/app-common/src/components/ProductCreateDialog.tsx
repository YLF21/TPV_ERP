import { useEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import { ApiError, apiRequest } from "../api/client";
import { apiBaseUrl } from "../api/runtime";
import type { LocaleCode } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";
import deleteImageIcon from "../assets/product/delete.png";
import { enterNavigationIntent } from "./keyboardNavigation";

export type ProductCreateDialogProps = {
  open: boolean;
  locale: LocaleCode;
  token?: string;
  editProduct?: ProductCreateEditProduct | null;
  initialForm?: Partial<ProductCreateFormState>;
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
  barcode2: string;
  salePrice: string;
  memberPrice: string;
  wholesalePrice: string;
  offerPrice: string;
  offerDiscountPercent: string;
  offerActive: boolean;
  offerFrom: string;
  offerUntil: string;
};

export type ProductCreateEditProduct = {
  id: string;
  form: Partial<ProductCreateFormState>;
  initialData?: ProductCreateInitialData;
};

export type ProductCreateInitialData = {
  discountType?: DiscountTypeCode | null;
  purchaseDiscountPercent?: string | number | null;
  packageQuantity?: string | number | null;
  stockMin?: string | number | null;
  stockMax?: string | number | null;
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
  | "barcode2"
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

type ProductIdentifierView = {
  id: string;
  code?: string | null;
  barcode?: string | null;
  barcode2?: string | null;
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
  productId?: string;
  initialData?: ProductCreateInitialData;
  createProduct?: (body: ReturnType<typeof buildCreateProductRequest>, token: string) => Promise<ProductCreateResponse>;
  updateProduct?: (productId: string, body: ReturnType<typeof buildCreateProductRequest>, token: string) => Promise<ProductCreateResponse>;
  uploadImage?: (productId: string, file: File, token: string) => Promise<void>;
};

export type SaveProductResult = {
  product: ProductCreateResponse;
  imageUploadFailed: boolean;
};

type ProductCreateKeyAction = "close" | "save";

const productTypeOptions: ProductTypeCode[] = ["UNIT", "WEIGHT", "SERVICE"];
export const productDiscountTypeOptions: PriceUseModeCode[] = ["NORMAL", "MEMBER_PRICE", "OFFER_PRICE", "OFFER_DISCOUNT"];

export function productCreateKeyAction(key: string): ProductCreateKeyAction | null {
  if (key === "Escape") {
    return "close";
  }
  if (key === "F9") {
    return "save";
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
    barcode2: "",
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

export function createProductFormFromEditProduct(product?: ProductCreateEditProduct | null): ProductCreateFormState {
  const form = {
    ...createDefaultProductForm(),
    ...(product?.form ?? {}),
    ...(product?.initialData?.discountType ? { discountType: product.initialData.discountType } : {})
  };
  if (form.discountType === "NONE") {
    return {
      ...form,
      priceUseMode: "NORMAL",
      offerActive: false
    };
  }
  return form;
}

export function createProductFormFromInitial(
  product?: ProductCreateEditProduct | null,
  initialForm?: Partial<ProductCreateFormState>
): ProductCreateFormState {
  if (product) {
    return createProductFormFromEditProduct(product);
  }
  return {
    ...createDefaultProductForm(),
    ...(initialForm ?? {})
  };
}

export function applyProductRequiredDefaults(
  form: ProductCreateFormState,
  families: FamilyView[] = [],
  taxes: TaxView[] = []
): ProductCreateFormState {
  return {
    ...form,
    familyId: form.familyId || families.find((family) => family.defaultFamily)?.id || families[0]?.id || "",
    taxId: form.taxId || taxes.find((tax) => tax.defaultTax)?.id || taxes[0]?.id || ""
  };
}

export function buildCreateProductRequest(
  form: ProductCreateFormState,
  initialData?: ProductCreateInitialData
) {
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
    code: emptyToNull(form.code),
    barcode: emptyToNull(form.barcode),
    barcode2: emptyToNull(form.barcode2),
    salePrice: decimalOrZero(form.salePrice),
    memberPrice: emptyToNull(form.memberPrice),
    wholesalePrice: emptyToNull(form.wholesalePrice),
    offerPrice: noDiscountLocked ? null : emptyToNull(offerPrice),
    offerDiscountPercent: noDiscountLocked ? null : emptyToNull(form.offerDiscountPercent),
    purchaseDiscountPercent: preservedNullableDecimal(initialData?.purchaseDiscountPercent),
    packageQuantity: preservedNullableDecimal(initialData?.packageQuantity) ?? "1",
    stockMin: preservedNullableDecimal(initialData?.stockMin),
    stockMax: preservedNullableDecimal(initialData?.stockMax),
    offerActive,
    offerFrom: emptyToNull(form.offerFrom),
    offerUntil: emptyToNull(form.offerUntil)
  };
}

function preservedNullableDecimal(value: string | number | null | undefined) {
  if (value === null || value === undefined) {
    return null;
  }
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : null;
  }
  const trimmed = value.trim();
  return trimmed.length === 0 ? null : trimmed;
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

function canSubmitProduct(form: ProductCreateFormState, products: ProductIdentifierView[] = [], currentProductId?: string | null) {
  return productCreateValidationErrors(form, products, currentProductId).length === 0;
}

export function productCreateValidationErrors(
  form: ProductCreateFormState,
  products: ProductIdentifierView[] = [],
  currentProductId?: string | null
) {
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
  if (!form.code.trim() && !form.barcode.trim()) {
    errors.push("code");
    errors.push("barcode");
  }
  const duplicatedFields = duplicatedProductIdentifierFields(form, products, currentProductId);
  duplicatedFields.forEach((field) => errors.push(field));
  if (duplicatedFields.length > 0) {
    errors.push("identifierDuplicate");
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
  if (fieldName === "code" || fieldName === "barcode") {
    return Boolean(form.code.trim() || form.barcode.trim());
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
    return true;
  }
  return true;
}

export function canNavigateProductField(
  form: ProductCreateFormState,
  fieldName: string | null | undefined,
  backwards: boolean
) {
  const movingBetweenEmptyPrimaryIdentifiers = !form.code.trim() && !form.barcode.trim() && (
    (fieldName === "barcode" && !backwards)
    || (fieldName === "code" && backwards)
  );
  return movingBetweenEmptyPrimaryIdentifiers || canLeaveProductField(form, fieldName);
}

function normalizeIdentifier(value: string | null | undefined) {
  return value?.trim().toLocaleUpperCase() ?? "";
}

export function duplicatedProductIdentifierFields(
  form: ProductCreateFormState,
  products: ProductIdentifierView[],
  currentProductId?: string | null
) {
  const fields: Array<"code" | "barcode" | "barcode2"> = ["code", "barcode", "barcode2"];
  const duplicated = new Set<"code" | "barcode" | "barcode2">();
  const currentValues = new Map<string, Array<"code" | "barcode" | "barcode2">>();

  fields.forEach((field) => {
    const normalized = normalizeIdentifier(form[field]);
    if (!normalized) {
      return;
    }
    currentValues.set(normalized, [...(currentValues.get(normalized) ?? []), field]);
  });
  currentValues.forEach((matchingFields) => {
    if (matchingFields.length > 1) {
      const samePrimaryIdentifier = matchingFields.every((field) => field === "code" || field === "barcode");
      if (samePrimaryIdentifier) {
        return;
      }
      matchingFields.forEach((field) => duplicated.add(field));
    }
  });

  const existingValues = new Set<string>();
  products
    .filter((product) => product.id !== currentProductId)
    .forEach((product) => {
      [product.code, product.barcode, product.barcode2].forEach((value) => {
        const normalized = normalizeIdentifier(value);
        if (normalized) {
          existingValues.add(normalized);
        }
      });
    });

  fields.forEach((field) => {
    if (existingValues.has(normalizeIdentifier(form[field]))) {
      duplicated.add(field);
    }
  });

  return fields.filter((field) => duplicated.has(field));
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

function dateRangeDayCount(from: string, to: string) {
  const start = parseIsoDate(from);
  const end = parseIsoDate(to || from);
  if (!start || !end) {
    return 0;
  }
  return Math.max(1, Math.round((end.getTime() - start.getTime()) / 86_400_000) + 1);
}

function selectedDaysText(count: number, locale: LocaleCode) {
  if (locale === "zh") {
    return `已选择 ${count} 天`;
  }
  if (locale === "en") {
    return `${count} days selected`;
  }
  return `${count} días seleccionados`;
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

async function updateProductRequest(productId: string, body: ReturnType<typeof buildCreateProductRequest>, token: string) {
  return apiRequest<ProductCreateResponse>(`/products/${encodeURIComponent(productId)}`, {
    method: "PUT",
    token,
    body
  });
}

export async function saveProductWithOptionalImage({
  form,
  token,
  imageFile,
  productId,
  initialData,
  createProduct = createProductRequest,
  updateProduct = updateProductRequest,
  uploadImage = uploadProductImage
}: SaveProductOptions): Promise<SaveProductResult> {
  const product = productId
    ? await updateProduct(productId, buildCreateProductRequest(form, initialData), token)
    : await createProduct(buildCreateProductRequest(form, initialData), token);
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

export function productCreateErrorMessage(error: unknown, fallback: string, conflictFallback = fallback) {
  if (error instanceof TypeError || (error instanceof Error && error.message === "Failed to write request")) {
    return fallback;
  }
  if (error instanceof ApiError && error.status === 409) {
    const code = typeof error.problem?.code === "string" ? error.problem.code : "";
    if (code === "DATA_INTEGRITY_CONFLICT" || code === "STATE_CONFLICT") {
      return conflictFallback;
    }
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
  editProduct,
  initialForm,
  onClose,
  onCreated
}: ProductCreateDialogProps) {
  const t = createTranslator(locale);
  const initialFormSignature = JSON.stringify(initialForm ?? {});
  const [form, setForm] = useState<ProductCreateFormState>(() => createProductFormFromInitial(editProduct, initialForm));
  const [families, setFamilies] = useState<FamilyView[]>([]);
  const [subfamilies, setSubfamilies] = useState<SubfamilyView[]>([]);
  const [taxes, setTaxes] = useState<TaxView[]>([]);
  const [products, setProducts] = useState<ProductIdentifierView[]>([]);
  const [status, setStatus] = useState("");
  const [saving, setSaving] = useState(false);
  const [touchedErrors, setTouchedErrors] = useState<string[]>([]);
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
  const editingProduct = Boolean(editProduct?.id);
  const validationErrors = productCreateValidationErrors(form, products, editProduct?.id);
  const invalidFields = new Set(validationErrors);

  function resetDialogState() {
    setForm(createProductFormFromInitial(editProduct, initialForm));
    setStatus("");
    setTouchedErrors([]);
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
    setForm(createDefaultProductForm());
    setStatus("");
    setTouchedErrors([]);
    setSaving(false);
    setOpenDropdown("");
    setFamilyPickerOpen(false);
    setSelectedProductFamily({ familyId: "", subfamilyId: "" });
    setOfferPickerOpen(false);
    setOfferRangeStart(null);
    setCalendarMonth(startOfMonth(new Date()));
    changeImage(null);
    onClose();
  }

  useEffect(() => {
    if (!open) {
      return;
    }
    const nextForm = createProductFormFromInitial(editProduct, initialForm);
    setForm(nextForm);
    setSelectedProductFamily({
      familyId: nextForm.familyId,
      subfamilyId: nextForm.subfamilyId
    });
    setStatus("");
    setSaving(false);
    setOpenDropdown("");
    setFamilyPickerOpen(false);
    setOfferPickerOpen(false);
    setOfferRangeStart(null);
    setCalendarMonth(startOfMonth(new Date()));
    changeImage(null);
  }, [open, editProduct?.id, initialFormSignature]);

  useEffect(() => {
    if (!open || !token) {
      return;
    }

    let cancelled = false;
    async function loadCatalog() {
      setStatus("");
      try {
        const [nextFamilies, nextTaxes, nextProducts] = await Promise.all([
          apiRequest<FamilyView[]>("/families", { token }),
          apiRequest<TaxView[]>("/taxes/selectable", { token }),
          apiRequest<ProductIdentifierView[]>("/products", { token })
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
        setProducts(nextProducts);
        setForm((current) => applyProductRequiredDefaults(current, nextFamilies, nextTaxes));
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
    if (!open || familyPickerOpen) {
      return;
    }
    setSelectedProductFamily({ familyId: form.familyId, subfamilyId: form.subfamilyId });
  }, [open, familyPickerOpen, form.familyId, form.subfamilyId]);

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
      void submitProduct(true);
    }

    window.addEventListener("keydown", handleProductShortcut);
    return () => window.removeEventListener("keydown", handleProductShortcut);
  }, [open, form, token, imageFile, saving]);

  useEffect(() => {
    if (!open || (!openDropdown && !offerPickerOpen)) {
      return;
    }
    function closeProductDropdownsOnOutsidePointer(event: PointerEvent) {
      const target = event.target;
      if (!(target instanceof Element)) {
        setOpenDropdown("");
        setOfferPickerOpen(false);
        return;
      }
      if (
        target.closest(".product-dropdown-field.open")
        || target.closest(".product-select-popover")
        || target.closest(".product-offer-range.open")
        || target.closest(".date-range-popover")
      ) {
        return;
      }
      setOpenDropdown("");
      setOfferPickerOpen(false);
    }
    document.addEventListener("pointerdown", closeProductDropdownsOnOutsidePointer, true);
    return () => document.removeEventListener("pointerdown", closeProductDropdownsOnOutsidePointer, true);
  }, [open, openDropdown, offerPickerOpen]);

  if (!open) {
    return null;
  }

  function updateField<K extends keyof ProductCreateFormState>(key: K, value: ProductCreateFormState[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  function requiredLabel(key: string, required = false) {
    return `${t(key)}${required ? " *" : ""}`;
  }

  function fieldClass(fieldName: string, base = "") {
    const invalid = touchedErrors.includes(fieldName) && invalidFields.has(fieldName);
    return [base, invalid ? "field-invalid" : ""].filter(Boolean).join(" ");
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

  function productFieldElements() {
    return Array.from(formRef.current?.querySelectorAll<HTMLElement>("[data-product-field]") ?? [])
      .filter((field) => productFieldFocusTarget(field));
  }

  function productFieldFocusTarget(field: HTMLElement) {
    if (field.matches("input:not(:disabled), textarea:not(:disabled), button:not(:disabled), select:not(:disabled), [tabindex]:not([tabindex='-1'])")) {
      return field;
    }
    return field.querySelector<HTMLElement>(
      "[aria-checked='true']:not(:disabled), input:not(:disabled), textarea:not(:disabled), button:not(:disabled), select:not(:disabled), [tabindex]:not([tabindex='-1'])"
    );
  }

  function focusProductFieldTarget(target: HTMLElement | null | undefined) {
    if (!target) {
      return;
    }
    target.focus();
    if (target instanceof HTMLTextAreaElement) {
      target.select();
      return;
    }
    if (target instanceof HTMLInputElement && !["checkbox", "radio", "file", "button"].includes(target.type)) {
      target.select();
    }
  }

  function focusAdjacentProductField(fieldName: string | undefined, backwards: boolean) {
    const fields = productFieldElements();
    const currentIndex = fields.findIndex((field) => field.dataset.productFieldName === fieldName);
    const nextIndex = nextProductFieldIndex(currentIndex, fields.length, backwards);
    if (nextIndex < 0) {
      return;
    }
    focusProductFieldTarget(productFieldFocusTarget(fields[nextIndex]));
  }

  function focusAdjacentProductFieldAfterRender(fieldName: string, backwards = false) {
    window.requestAnimationFrame(() => focusAdjacentProductField(fieldName, backwards));
  }

  function focusProductDropdownOption(name: string, selectedValue: string, fromEnd = false) {
    window.requestAnimationFrame(() => {
      const options = Array.from(
        formRef.current?.querySelectorAll<HTMLButtonElement>(`[data-product-dropdown="${name}"] [data-product-option]`) ?? []
      ).filter((option) => !option.disabled);
      if (options.length === 0) {
        return;
      }
      const selected = options.find((option) => option.dataset.productOptionValue === selectedValue);
      const target = selected ?? options[fromEnd ? options.length - 1 : 0];
      target?.focus();
    });
  }

  function focusAdjacentProductDropdownOption(currentOption: HTMLElement, backwards: boolean) {
    const popover = currentOption.closest<HTMLElement>("[data-product-dropdown]");
    const options = Array.from(popover?.querySelectorAll<HTMLButtonElement>("[data-product-option]") ?? [])
      .filter((option) => !option.disabled);
    const currentIndex = options.findIndex((option) => option === currentOption);
    const nextIndex = nextProductFieldIndex(currentIndex, options.length, backwards);
    if (nextIndex >= 0) {
      options[nextIndex]?.focus();
    }
  }

  function offerCalendarDays() {
    return Array.from(formRef.current?.querySelectorAll<HTMLButtonElement>(".date-range-popover .date-day:not(.empty)") ?? [])
      .filter((day) => !day.disabled);
  }

  function focusOfferCalendarDay(fromEnd = false) {
    window.requestAnimationFrame(() => {
      const days = offerCalendarDays();
      const selected = days.find((day) => day.classList.contains("selected"));
      const target = selected ?? days[fromEnd ? days.length - 1 : 0];
      target?.focus();
    });
  }

  function focusRelativeOfferCalendarDay(currentDay: HTMLElement, offset: number) {
    const days = offerCalendarDays();
    const currentIndex = days.findIndex((day) => day === currentDay);
    if (currentIndex < 0 || days.length === 0) {
      return;
    }
    const nextIndex = Math.min(days.length - 1, Math.max(0, currentIndex + offset));
    days[nextIndex]?.focus();
  }

  function focusProductField(event: KeyboardEvent<HTMLElement>) {
    const intent = enterNavigationIntent(event.key, {
      shiftKey: event.shiftKey,
      altKey: event.altKey,
      ctrlKey: event.ctrlKey,
      metaKey: event.metaKey,
      isComposing: event.nativeEvent.isComposing
    });
    if (!intent) return;
    if (event.defaultPrevented) return;
    const target = event.target as HTMLElement;
    if (target.closest(".filter-popover") || target.closest(".date-popover")) {
      return;
    }
    const fieldName = target.closest<HTMLElement>("[data-product-field-name]")?.dataset.productFieldName;
    if (target.matches("button")) {
      if (target.hasAttribute("data-product-field")) {
        event.preventDefault();
        focusAdjacentProductField(fieldName, intent === "previous");
      }
      return;
    }
    const duplicateInvalid = fieldName === "code" || fieldName === "barcode" || fieldName === "barcode2"
      ? duplicatedProductIdentifierFields(form, products, editProduct?.id).includes(fieldName)
      : false;
    if (!canNavigateProductField(form, fieldName, intent === "previous") || duplicateInvalid) {
      event.preventDefault();
      const nextErrors = productCreateValidationErrors(form, products, editProduct?.id);
      setTouchedErrors(nextErrors);
      setStatus(nextErrors.includes("identifierDuplicate") ? t("product.create.duplicateIdentifier") : t("product.create.required"));
      target.focus();
      return;
    }
    setStatus("");
    event.preventDefault();
    if (target.matches("input[type='checkbox'], input[type='radio']") && intent === "next") {
      (target as HTMLInputElement).click();
    }
    focusAdjacentProductField(fieldName, intent === "previous");
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
    focusAdjacentProductFieldAfterRender("familyId");
  }

  function selectOfferDate(date: Date, advanceAfterSelection = false) {
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
    if (advanceAfterSelection) {
      focusAdjacentProductFieldAfterRender("offerRange");
    }
  }

  function clearOfferEndDate(advanceAfterSelection = false) {
    setForm((current) => ({ ...current, offerUntil: "" }));
    setOfferRangeStart(null);
    setOfferPickerOpen(false);
    if (advanceAfterSelection) {
      focusAdjacentProductFieldAfterRender("offerRange");
    }
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
      <div className={`filter-field product-dropdown-field ${isOpen ? "open" : ""} ${fieldClass(fieldName)}`}>
        <span>{label}</span>
        <button
          type="button"
          className="filter-select-button"
          data-product-field
          data-product-field-name={fieldName}
          aria-expanded={isOpen}
          aria-haspopup="listbox"
          onClick={() => {
            setFamilyPickerOpen(false);
            setOpenDropdown((current) => current === name ? "" : name);
          }}
          onKeyDown={(event) => {
            if (event.key === "ArrowDown" || event.key === "ArrowUp" || event.key === "Home" || event.key === "End") {
              event.preventDefault();
              setFamilyPickerOpen(false);
              setOpenDropdown(name);
              focusProductDropdownOption(name, value, event.key === "ArrowUp" || event.key === "End");
              return;
            }
            if (event.key === "Escape" && isOpen) {
              event.preventDefault();
              setOpenDropdown("");
              return;
            }
            if (event.key === "Enter" && isOpen) {
              event.preventDefault();
              setOpenDropdown("");
              focusAdjacentProductFieldAfterRender(fieldName);
            }
          }}
        >
          <span>{selectedLabel}</span>
          <span className="filter-control-arrow">v</span>
        </button>
        {isOpen && (
          <div className="filter-popover product-select-popover" data-product-dropdown={name} role="listbox">
            {options.map((option) => (
              <button
                type="button"
                className={option.value === value ? "selected" : ""}
                data-product-option
                data-product-option-value={option.value}
                key={option.value}
                role="option"
                aria-selected={option.value === value}
                onClick={() => {
                  onChange(option.value);
                  setOpenDropdown("");
                }}
                onKeyDown={(event) => {
                  const intent = enterNavigationIntent(event.key, {
                    shiftKey: event.shiftKey,
                    altKey: event.altKey,
                    ctrlKey: event.ctrlKey,
                    metaKey: event.metaKey,
                    isComposing: event.nativeEvent.isComposing
                  });
                  if (!intent) return;
                  event.preventDefault();
                  setOpenDropdown("");
                  if (intent === "next") onChange(option.value);
                  focusAdjacentProductFieldAfterRender(fieldName, intent === "previous");
                  return;
                }}
                onKeyDownCapture={(event) => {
                  if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                    event.preventDefault();
                    focusAdjacentProductDropdownOption(event.currentTarget, event.key === "ArrowUp");
                    return;
                  }
                  if (event.key === "Home" || event.key === "End") {
                    event.preventDefault();
                    focusProductDropdownOption(name, "", event.key === "End");
                    return;
                  }
                  if (event.key === "Escape") {
                    event.preventDefault();
                    setOpenDropdown("");
                    window.requestAnimationFrame(() => {
                      formRef.current?.querySelector<HTMLElement>(`[data-product-field-name="${fieldName}"]`)?.focus();
                    });
                  }
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
                onKeyDown={(event) => {
                  const intent = enterNavigationIntent(event.key, {
                    shiftKey: event.shiftKey,
                    altKey: event.altKey,
                    ctrlKey: event.ctrlKey,
                    metaKey: event.metaKey,
                    isComposing: event.nativeEvent.isComposing
                  });
                  if (!intent) return;
                  event.preventDefault();
                  if (intent === "next") updatePriceUseMode(mode);
                  focusAdjacentProductFieldAfterRender("priceUseMode", intent === "previous");
                }}
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
    const formForSave = applyProductRequiredDefaults(form, families, taxes);
    const nextErrors = productCreateValidationErrors(formForSave, products, editProduct?.id);
    if (saving || !token || nextErrors.length > 0) {
      setTouchedErrors(nextErrors);
      setForm(formForSave);
      setStatus(nextErrors.includes("identifierDuplicate") ? t("product.create.duplicateIdentifier") : t("product.create.required"));
      return;
    }
    setForm(formForSave);
    setSaving(true);
    setStatus("");
    try {
      const result = await saveProductWithOptionalImage({
        form: formForSave,
        token,
        imageFile,
        productId: editProduct?.id,
        initialData: editProduct?.initialData
      });
      onCreated?.(result.product);
      resetDialogState();
      if (closeAfterSave) {
        onClose();
      }
    } catch (error) {
      setStatus(productCreateErrorMessage(error, t("product.create.saveError"), t("product.create.duplicateIdentifier")));
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
            <h2 id="product-create-title">{t(editingProduct ? "product.edit.title" : "product.create.title")}</h2>
            <span>{selectedFamily?.name ?? t("product.create.subtitle")}</span>
          </div>
          <button type="button" onClick={closeProductDialog}>{t("common.close")}</button>
        </header>

        <div className="product-create-body" ref={formRef} onKeyDown={focusProductField}>
          <div className="product-create-form">
            <div className="product-create-row product-create-row-two">
              <label className={fieldClass("barcode")}>
                <span>{requiredLabel("stock.column.barcode", true)}</span>
                <input data-product-field data-product-field-name="barcode" value={form.barcode} onChange={(event) => updateField("barcode", event.target.value)} autoFocus />
              </label>
              <label className={fieldClass("code")}>
                <span>{requiredLabel("stock.column.code", true)}</span>
                <input data-product-field data-product-field-name="code" value={form.code} onChange={(event) => updateField("code", event.target.value)} />
              </label>
            </div>
            <div className="product-create-row product-create-row-name-type">
              <label className={fieldClass("name")}>
                <span>{requiredLabel("product.field.name", true)}</span>
                <input data-product-field data-product-field-name="name" required value={form.name} onChange={(event) => updateField("name", event.target.value)} />
              </label>
              {renderDropdown(
                "type",
                requiredLabel("stock.column.type", true),
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
              <div className={`filter-field product-family-field ${familyPickerOpen ? "open" : ""} ${fieldClass("familyId")}`}>
                <span>{requiredLabel("stock.column.family", true)}</span>
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
                requiredLabel("stock.column.tax", true),
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
              <label className={fieldClass("purchasePrice")}>
                <span>{requiredLabel("stock.column.purchasePrice", true)}</span>
                <input data-product-field data-product-field-name="purchasePrice" required inputMode="decimal" value={form.purchasePrice} onChange={(event) => updateField("purchasePrice", event.target.value)} />
              </label>
              <label className={fieldClass("salePrice")}>
                <span>{requiredLabel("stock.column.salePrice", true)}</span>
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
              <label className={fieldClass("offerPrice")}>
                <span>{t("stock.column.offerPrice")}</span>
                <input data-product-field data-product-field-name="offerPrice" inputMode="decimal" value={form.offerPrice} onChange={(event) => updateField("offerPrice", event.target.value)} />
              </label>
              <label>
                <span>{t("product.field.offerDiscountPercent")}</span>
                <input data-product-field data-product-field-name="offerDiscountPercent" inputMode="decimal" value={form.offerDiscountPercent} onChange={(event) => updateOfferDiscountPercent(event.target.value)} />
              </label>
              <div className={`filter-field product-offer-range ${offerPickerOpen ? "open" : ""}`}>
                <span>{requiredLabel("product.field.offerRange", offerActive)}</span>
                <div className="date-range-control">
                  <input type="text" value={formatOfferRange(form.offerFrom, form.offerUntil)} readOnly placeholder="01/07/2026-31/07/2026" />
                  <button
                    type="button"
                    data-product-field
                    data-product-field-name="offerRange"
                    aria-expanded={offerPickerOpen}
                    aria-haspopup="dialog"
                    aria-label={t("salesReport.filter.openCalendar")}
                    onClick={() => setOfferPickerOpen((current) => !current)}
                    onKeyDown={(event) => {
                      if (event.key === "ArrowDown" || event.key === "ArrowUp" || event.key === "Home" || event.key === "End") {
                        event.preventDefault();
                        setOfferPickerOpen(true);
                        focusOfferCalendarDay(event.key === "ArrowUp" || event.key === "End");
                        return;
                      }
                      if (event.key === "Escape" && offerPickerOpen) {
                        event.preventDefault();
                        setOfferPickerOpen(false);
                        return;
                      }
                      if (event.key === "Enter" && offerPickerOpen) {
                        event.preventDefault();
                        setOfferPickerOpen(false);
                        focusAdjacentProductFieldAfterRender("offerRange");
                      }
                    }}
                  >
                    <span className="filter-control-arrow">v</span>
                  </button>
                </div>
                {offerPickerOpen && (
                  <div className="date-popover date-range-popover">
                    <div className="date-range-strip">
                      <div className={`date-range-strip-cell ${offerRangeStart ? "" : "active"}`}>
                        <span>{t("salesReport.filter.dateFrom")}</span>
                        <strong>{form.offerFrom ? formatShortDate(form.offerFrom) : "-"}</strong>
                      </div>
                      <div className={`date-range-strip-cell ${offerRangeStart ? "active" : ""}`}>
                        <span>{t("salesReport.filter.dateTo")}</span>
                        <strong>{form.offerUntil ? formatShortDate(form.offerUntil) : t("product.field.noEnd")}</strong>
                      </div>
                    </div>
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
                          onKeyDown={(event) => {
                            if (event.key === "ArrowRight" || event.key === "ArrowLeft" || event.key === "ArrowDown" || event.key === "ArrowUp") {
                              event.preventDefault();
                              const offset = event.key === "ArrowRight" ? 1
                                : event.key === "ArrowLeft" ? -1
                                  : event.key === "ArrowDown" ? 7
                                    : -7;
                              focusRelativeOfferCalendarDay(event.currentTarget, offset);
                              return;
                            }
                            if (event.key === "Home" || event.key === "End") {
                              event.preventDefault();
                              focusOfferCalendarDay(event.key === "End");
                              return;
                            }
                            if (event.key === "Escape") {
                              event.preventDefault();
                              setOfferPickerOpen(false);
                              window.requestAnimationFrame(() => {
                                formRef.current?.querySelector<HTMLElement>("[data-product-field-name='offerRange']")?.focus();
                              });
                              return;
                            }
                            const intent = enterNavigationIntent(event.key, {
                              shiftKey: event.shiftKey,
                              altKey: event.altKey,
                              ctrlKey: event.ctrlKey,
                              metaKey: event.metaKey,
                              isComposing: event.nativeEvent.isComposing
                            });
                            if (!intent) return;
                            event.preventDefault();
                            if (intent === "next") {
                              selectOfferDate(day, true);
                            } else {
                              setOfferPickerOpen(false);
                              focusAdjacentProductFieldAfterRender("offerRange", true);
                            }
                          }}
                        >
                          {day.getDate()}
                        </button>
                      ) : <span className="date-day empty" key={`empty-${index}`} />)}
                    </div>
                    <footer className="date-range-footer">
                      <span>{form.offerFrom ? selectedDaysText(dateRangeDayCount(form.offerFrom, form.offerUntil), locale) : t("salesReport.filter.pickDateFrom")}</span>
                      <div className="date-range-actions">
                        <button type="button" onClick={() => {
                          setOfferRangeStart(null);
                          setOfferPickerOpen(false);
                        }}>
                          {t("common.cancel")}
                        </button>
                        <button type="button" onClick={() => clearOfferEndDate()}>
                          {t("product.field.noEnd")}
                        </button>
                        <button type="button" className="primary" onClick={() => {
                          setOfferRangeStart(null);
                          setOfferPickerOpen(false);
                          focusAdjacentProductFieldAfterRender("offerRange");
                        }}>
                          {t("common.apply")}
                        </button>
                      </div>
                    </footer>
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
                onKeyDown={(event) => {
                  const intent = enterNavigationIntent(event.key, {
                    shiftKey: event.shiftKey,
                    altKey: event.altKey,
                    ctrlKey: event.ctrlKey,
                    metaKey: event.metaKey,
                    isComposing: event.nativeEvent.isComposing
                  });
                  if (!intent) return;
                  event.preventDefault();
                  if (intent === "next") toggleNoDiscountLock();
                  focusAdjacentProductFieldAfterRender("discountType", intent === "previous");
                }}
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
            <label className={fieldClass("barcode2", "product-secondary-barcode")}>
              <span>{t("stock.column.barcode2")}</span>
              <input data-product-field data-product-field-name="barcode2" value={form.barcode2} onChange={(event) => updateField("barcode2", event.target.value)} />
            </label>
          </aside>
        </div>

        {status && <p className="product-create-status">{status}</p>}
        <footer className="filter-actions">
          <button type="button" onClick={closeProductDialog}>{t("common.close")}</button>
          <button type="button" disabled={saving} onClick={() => void submitProduct(true)}>
            {saving ? t("product.create.saving") : t("product.create.save")}
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
                          focusAdjacentProductFieldAfterRender("familyId");
                        }}
                        onKeyDown={(event) => {
                          const intent = enterNavigationIntent(event.key, {
                            shiftKey: event.shiftKey,
                            altKey: event.altKey,
                            ctrlKey: event.ctrlKey,
                            metaKey: event.metaKey,
                            isComposing: event.nativeEvent.isComposing
                          });
                          if (!intent) return;
                          event.preventDefault();
                          setFamilyPickerOpen(false);
                          if (intent === "next") {
                            setSelectedProductFamily({ familyId: family.id, subfamilyId: "" });
                            setForm((current) => ({ ...current, familyId: family.id, subfamilyId: "" }));
                          }
                          focusAdjacentProductFieldAfterRender("familyId", intent === "previous");
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
                                focusAdjacentProductFieldAfterRender("familyId");
                              }}
                              onKeyDown={(event) => {
                                const intent = enterNavigationIntent(event.key, {
                                  shiftKey: event.shiftKey,
                                  altKey: event.altKey,
                                  ctrlKey: event.ctrlKey,
                                  metaKey: event.metaKey,
                                  isComposing: event.nativeEvent.isComposing
                                });
                                if (!intent) return;
                                event.preventDefault();
                                setFamilyPickerOpen(false);
                                if (intent === "next") {
                                  setSelectedProductFamily({ familyId: family.id, subfamilyId: subfamily.id });
                                  setForm((current) => ({ ...current, familyId: family.id, subfamilyId: subfamily.id }));
                                }
                                focusAdjacentProductFieldAfterRender("familyId", intent === "previous");
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
