import { apiRequest } from "../api/client";

export type InvoiceBankAccountView = {
  id: string;
  bankName: string;
  iban: string;
  displayIban: string;
  active: boolean;
  order: number;
};

export type InvoicePrintConfigurationView = {
  observations: string | null;
  bankAccounts: InvoiceBankAccountView[];
};

export async function loadInvoicePrintConfiguration(
  token: string | undefined,
  request: typeof apiRequest = apiRequest,
) {
  return request<InvoicePrintConfigurationView>(
    "/invoice-print-configuration",
    { token },
  );
}

export async function saveInvoiceObservations(
  observations: string,
  token: string | undefined,
  request: typeof apiRequest = apiRequest,
) {
  return request<InvoicePrintConfigurationView>(
    "/invoice-print-configuration/observations",
    { token, method: "PUT", body: { observations } },
  );
}

export async function addInvoiceBankAccount(
  bankName: string,
  iban: string,
  token: string | undefined,
  request: typeof apiRequest = apiRequest,
) {
  return request<InvoiceBankAccountView>(
    "/invoice-print-configuration/bank-accounts",
    { token, method: "POST", body: { bankName, iban } },
  );
}

export async function setInvoiceBankAccountActive(
  id: string,
  active: boolean,
  token: string | undefined,
  request: typeof apiRequest = apiRequest,
) {
  return request<InvoiceBankAccountView>(
    `/invoice-print-configuration/bank-accounts/${encodeURIComponent(id)}/active`,
    { token, method: "PATCH", body: { active } },
  );
}
