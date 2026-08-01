import { apiRequest } from "../../../packages/app-common/src/api/client";

export type InternalEanConfiguration = {
  companyId: string;
  companyCode?: string | null;
  version: number;
  configured: boolean;
};

export function loadInternalEanConfiguration(token?: string) {
  return apiRequest<InternalEanConfiguration>("/internal-ean/configuration", { token });
}

export function saveInternalEanConfiguration(
  expectedVersion: number,
  companyCode: string,
  token?: string,
) {
  return apiRequest<InternalEanConfiguration>("/internal-ean/configuration", {
    method: "PUT",
    token,
    body: { expectedVersion, companyCode },
  });
}
