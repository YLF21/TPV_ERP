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
  ticketStyle: TicketPrintStyle;
};

export type TicketPrintStyle = "PRINCIPAL" | "COMPACTA" | "MINIMALISTA";

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
  value: { ticket: string; invoice: string; deliveryNote: string },
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
