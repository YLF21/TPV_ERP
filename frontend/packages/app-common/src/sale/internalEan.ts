import { apiRequest } from "../api/client";
import type { SaleOperationCredentials } from "./operationSecurity";

export type InternalEanFormat = "EAN_8" | "EAN_13";

export type InternalEanValidation = {
  code: string;
  format?: InternalEanFormat | null;
  valid: boolean;
  reason?: "NON_NUMERIC" | "INVALID_LENGTH" | "INVALID_CHECK_DIGIT" | string | null;
};

export type InternalEanReservation = {
  reservationId: string;
  format: InternalEanFormat;
  code: string;
  expiresAt: string;
};

export type InternalEanProduct = {
  id: string;
  code?: string | null;
  barcode?: string | null;
  barcode2?: string | null;
  name?: string | null;
};

export function isValidEan(code?: string | null) {
  const normalized = String(code ?? "").trim();
  if (!/^\d{8}$|^\d{13}$/.test(normalized)) return false;
  const body = normalized.slice(0, -1);
  let sum = 0;
  let weightThree = true;
  for (let index = body.length - 1; index >= 0; index -= 1) {
    sum += Number(body[index]) * (weightThree ? 3 : 1);
    weightThree = !weightThree;
  }
  return (10 - (sum % 10)) % 10 === Number(normalized.at(-1));
}

export function validateInternalEan(code: string, token?: string) {
  return apiRequest<InternalEanValidation>("/pos/internal-ean/validate", {
    method: "POST",
    token,
    body: { code },
  });
}

export function reserveInternalEan(
  format: InternalEanFormat,
  authorization: SaleOperationCredentials,
  token?: string,
) {
  return apiRequest<InternalEanReservation>("/pos/internal-ean/reservations", {
    method: "POST",
    token,
    body: { format, authorization },
  });
}

export function reserveManualEan(
  code: string,
  authorization: SaleOperationCredentials,
  token?: string,
) {
  return apiRequest<InternalEanReservation>("/pos/internal-ean/manual/reservations", {
    method: "POST",
    token,
    body: { code, authorization },
  });
}

export function assignInternalEan(
  reservationId: string,
  productId: string,
  replaceExisting: boolean,
  token?: string,
) {
  return apiRequest<InternalEanProduct>("/pos/internal-ean/assign-existing", {
    method: "POST",
    token,
    body: { reservationId, productId, replaceExisting },
  });
}

export function createProductFromInternalEan(
  reservationId: string,
  product: unknown,
  token?: string,
) {
  return apiRequest<InternalEanProduct>("/pos/internal-ean/create-product", {
    method: "POST",
    token,
    body: { reservationId, product },
  });
}

export async function uploadInternalEanProductImage(
  reservationId: string,
  productId: string,
  file: File,
  token: string,
) {
  const body = new FormData();
  body.append("file", file);
  const response = await fetch(
    `${(await import("../api/runtime")).apiBaseUrl}/pos/internal-ean/assignments/${encodeURIComponent(reservationId)}/products/${encodeURIComponent(productId)}/image`,
    { method: "PUT", headers: { Authorization: `Bearer ${token}` }, body },
  );
  if (!response.ok) {
    throw new Error("internal_ean_image_upload_failed");
  }
}
