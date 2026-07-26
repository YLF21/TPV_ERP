import { useEffect, useState } from "react";
import { apiRequest } from "../api/client";
import { apiBaseUrl } from "../api/runtime";

export type ProductInformationSupplierView = {
  supplierId: string;
  legalName: string;
  documentType?: string | null;
  documentNumber?: string | null;
  active: boolean;
  supplierReference?: string | null;
  principal: boolean;
  lastSupplier: boolean;
  grossPurchasePrice?: number | string | null;
  purchaseDiscount?: number | string | null;
  netPurchasePrice?: number | string | null;
  lastEntryAt?: string | null;
};

export type ProductInformationSupplierState = "idle" | "loading" | "loaded" | "error";

type ProductInformationResourcesOptions = {
  productId: string;
  imageId?: string | null;
  token?: string;
  canReadSuppliers: boolean;
};

function numericValue(value: unknown) {
  if (value === null || value === undefined || value === "") return null;
  const parsed = Number(String(value).replace(",", "."));
  return Number.isFinite(parsed) ? parsed : null;
}

export function calculateNetPurchasePrice(purchasePrice: unknown, discountPercent: unknown) {
  const price = numericValue(purchasePrice);
  if (price === null) return null;
  const discount = discountPercent === null || discountPercent === undefined || discountPercent === ""
    ? 0
    : numericValue(discountPercent);
  return discount === null ? null : price * (1 - discount / 100);
}

export function sortProductInformationSuppliers(suppliers: ProductInformationSupplierView[]) {
  return [...suppliers].sort((left, right) => {
    const principal = Number(right.principal) - Number(left.principal);
    if (principal !== 0) return principal;
    const last = Number(right.lastSupplier) - Number(left.lastSupplier);
    if (last !== 0) return last;
    return left.legalName.localeCompare(right.legalName, undefined, { sensitivity: "base" });
  });
}

export function useProductInformationResources({
  productId,
  imageId,
  token,
  canReadSuppliers,
}: ProductInformationResourcesOptions) {
  const [imageSource, setImageSource] = useState("");
  const [suppliers, setSuppliers] = useState<ProductInformationSupplierView[]>([]);
  const [supplierState, setSupplierState] = useState<ProductInformationSupplierState>("idle");

  useEffect(() => {
    if (!imageId || !token) {
      setImageSource("");
      return;
    }
    let active = true;
    let objectUrl = "";
    void fetch(`${apiBaseUrl}/products/${encodeURIComponent(productId)}/image`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then((response) => {
      if (!response.ok) throw new Error("product_image_unavailable");
      return response.blob();
    }).then((blob) => {
      if (!active) return;
      objectUrl = URL.createObjectURL(blob);
      setImageSource(objectUrl);
    }).catch(() => {
      if (active) setImageSource("");
    });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [imageId, productId, token]);

  useEffect(() => {
    if (!token || !canReadSuppliers) {
      setSuppliers([]);
      setSupplierState("idle");
      return;
    }
    let active = true;
    setSupplierState("loading");
    void apiRequest<ProductInformationSupplierView[]>(
      `/products/${encodeURIComponent(productId)}/suppliers`,
      { token },
    ).then((values) => {
      if (!active) return;
      setSuppliers(sortProductInformationSuppliers(values));
      setSupplierState("loaded");
    }).catch(() => {
      if (!active) return;
      setSuppliers([]);
      setSupplierState("error");
    });
    return () => {
      active = false;
    };
  }, [canReadSuppliers, productId, token]);

  return { imageSource, suppliers, supplierState };
}
