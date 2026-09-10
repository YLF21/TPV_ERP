import { apiProblemCode } from "../api/client";

export function customerDocumentType(type: string): string {
  if (type === "CIF") return "NIF";
  if (type === "OTRO") return "PASAPORTE";
  return type;
}

export function customerIdentityFailure(failure: unknown): { messageKey: string; documentField: boolean } | undefined {
  switch (apiProblemCode(failure)) {
    case "CUSTOMER_DOCUMENT_INVALID":
      return { messageKey: "party.customerIdentity.invalid", documentField: true };
    case "CUSTOMER_DOCUMENT_DUPLICATE":
      return { messageKey: "party.customerIdentity.duplicate", documentField: true };
    case "CUSTOMER_IDENTITY_SAAS_UNAVAILABLE":
      return { messageKey: "party.customerIdentity.unavailable", documentField: false };
    case "CUSTOMER_IDENTITY_CONFLICT":
      return { messageKey: "party.customerIdentity.conflict", documentField: false };
    default:
      return undefined;
  }
}
