import { ApiError, apiBaseUrl, apiRequest } from "@tpverp/app-common";

export type DocumentTemplateType = "FACTURA_VENTA" | "ALBARAN_VENTA" | "TICKET" | "VALE";
export type DocumentTemplateFormat = "A4" | "TICKET_80";
export type DocumentTemplateStatus = "DRAFT" | "VALIDATED" | "ACTIVE" | "RETIRED";
export type DocumentTemplateOrigin = "INTEGRATED" | "IMPORTED";

export type ResolvedDocumentTemplate = {
  id: string | null;
  type: DocumentTemplateType;
  format: DocumentTemplateFormat;
  scope: "STORE" | "COMPANY" | "SYSTEM";
  code: string;
  version: number;
  schemaVersion: number;
  artifactReference: string;
  sha256: string | null;
  builtIn: boolean;
};

export type DocumentTemplateView = {
  id: string;
  type: DocumentTemplateType;
  format: DocumentTemplateFormat;
  scope: "STORE" | "COMPANY" | "SYSTEM";
  code: string;
  version: number;
  name: string;
  status: DocumentTemplateStatus;
  schemaVersion: number | null;
  sha256: string | null;
  createdByUserId: string | null;
  createdAt: string;
  validatedAt: string | null;
  activatedAt: string | null;
  retiredAt: string | null;
  reactivatable: boolean;
};

export type DocumentTemplateCatalog = {
  effective: ResolvedDocumentTemplate | null;
  storeTemplates: DocumentTemplateView[];
};

export type DocumentTemplatePresentation = {
  type: DocumentTemplateType;
  format: DocumentTemplateFormat;
  origin: DocumentTemplateOrigin;
};

export function loadDocumentTemplateCatalog(
  type: DocumentTemplateType,
  format: DocumentTemplateFormat,
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<DocumentTemplateCatalog>(
    `/document-templates?type=${encodeURIComponent(type)}&format=${encodeURIComponent(format)}`,
    { token },
  );
}

export function loadDocumentTemplatePresentation(
  type: DocumentTemplateType,
  format: DocumentTemplateFormat,
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<DocumentTemplatePresentation>(
    `/document-templates/presentation?type=${encodeURIComponent(type)}&format=${encodeURIComponent(format)}`,
    { token },
  );
}

export function saveDocumentTemplatePresentation(
  value: {
    type: DocumentTemplateType;
    format: DocumentTemplateFormat;
    origin: DocumentTemplateOrigin;
  },
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<DocumentTemplatePresentation>("/document-templates/presentation", {
    method: "PUT",
    token,
    body: value,
  });
}

export function createDocumentTemplateDraft(
  value: { type: DocumentTemplateType; format: DocumentTemplateFormat; code: string; name: string },
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<DocumentTemplateView>("/document-templates/store-drafts", {
    method: "POST",
    token,
    body: value,
  });
}

export function uploadDocumentTemplateArtifact(
  templateId: string,
  files: File[],
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  const body = new FormData();
  files.forEach((file) => body.append("files", file));
  return request<DocumentTemplateView>(
    `/document-templates/${encodeURIComponent(templateId)}/artifact`,
    { method: "POST", token, body },
  );
}

export function activateDocumentTemplate(
  templateId: string,
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<DocumentTemplateView>(
    `/document-templates/${encodeURIComponent(templateId)}/activate`,
    { method: "POST", token },
  );
}

export function reactivateDocumentTemplate(
  templateId: string,
  token?: string,
  request: typeof apiRequest = apiRequest,
) {
  return request<DocumentTemplateView>(
    `/document-templates/${encodeURIComponent(templateId)}/reactivate`,
    { method: "POST", token },
  );
}

export async function downloadDocumentTemplateSource(
  template: Pick<DocumentTemplateView, "id" | "code" | "version">,
  token?: string,
) {
  const response = await fetch(
    `${apiBaseUrl}/document-templates/${encodeURIComponent(template.id)}/source`,
    { headers: token ? { Authorization: `Bearer ${token}` } : undefined },
  );
  if (!response.ok) {
    throw new ApiError(response.statusText || "document_template_download_failed", response.status);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  const bundle = response.headers.get("content-type")?.includes("application/zip");
  anchor.download = `${template.code.toLowerCase()}_v${template.version}.${bundle ? "zip" : "jrxml"}`;
  anchor.click();
  URL.revokeObjectURL(url);
}
