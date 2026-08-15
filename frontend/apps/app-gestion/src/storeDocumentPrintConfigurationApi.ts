import { apiRequest } from "@tpverp/app-common";

export type StoreDocumentPrintLogo = {
  id: string;
  contentType: "image/png" | "image/jpeg";
  sha256: string;
  createdAt: string;
  dataUri: string;
};

export type StoreDocumentPrintConfiguration = {
  storeId: string;
  logo: StoreDocumentPrintLogo | null;
  ticketObservations: string | null;
  invoiceObservations: string | null;
  deliveryNoteObservations: string | null;
  voucherObservations: string | null;
  ticketStyle: TicketPrintStyle;
  ticketTemplateOrigin: TicketTemplateOrigin;
  showStoreName: boolean;
};

export type TicketPrintStyle = "PRINCIPAL" | "COMPACTA" | "MINIMALISTA";
export type TicketTemplateOrigin = "INTEGRATED" | "IMPORTED";

export function loadStoreDocumentPrintConfiguration(
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<StoreDocumentPrintConfiguration>(
    "/store-document-print-configuration",
    { token },
  );
}

export function saveStoreDocumentObservations(
  value: { ticket: string; invoice: string; deliveryNote: string; voucher: string },
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<StoreDocumentPrintConfiguration>(
    "/store-document-print-configuration/observations",
    { method: "PUT", token, body: value },
  );
}

export function uploadStoreDocumentLogo(
  file: File,
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  const body = new FormData();
  body.append("file", file);
  return request<StoreDocumentPrintConfiguration>(
    "/store-document-print-configuration/logo",
    { method: "PUT", token, body },
  );
}

export function removeStoreDocumentLogo(
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<StoreDocumentPrintConfiguration>(
    "/store-document-print-configuration/logo",
    { method: "DELETE", token },
  );
}

export function saveStoreTicketStyle(
  style: TicketPrintStyle,
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<StoreDocumentPrintConfiguration>(
    "/store-document-print-configuration/ticket-style",
    { method: "PUT", token, body: { style } },
  );
}

export function saveStoreNameVisibility(
  showStoreName: boolean,
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<StoreDocumentPrintConfiguration>(
    "/store-document-print-configuration/store-name-visibility",
    { method: "PUT", token, body: { showStoreName } },
  );
}

export function saveStoreTicketPresentation(
  origin: TicketTemplateOrigin,
  style: TicketPrintStyle,
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<StoreDocumentPrintConfiguration>(
    "/store-document-print-configuration/ticket-presentation",
    { method: "PUT", token, body: { origin, style } },
  );
}
