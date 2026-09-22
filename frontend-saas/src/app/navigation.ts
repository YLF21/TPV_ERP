import type { View } from "../shared/types";
import type { WorkspaceLabelKey } from "../i18n/workspace";

export const navigationGroups = ["home", "clients", "ownCompany", "system", "technical", "security"] as const;

export const navigation: {
  view: View;
  label: string;
  group: typeof navigationGroups[number];
  permission?: string;
  phase?: WorkspaceLabelKey;
  description?: WorkspaceLabelKey;
}[] = [
  { view: "dashboard", label: "dashboard", group: "home" },
  { view: "reports", label: "reports", group: "home", permission: "VIEW_REPORTS", description: "reportsHelp" },
  { view: "companies", label: "companies", group: "clients", description: "companiesHelp" },
  { view: "stores", label: "stores", group: "clients" },
  { view: "licenses", label: "activeLicenses", group: "clients" },
  {
    view: "create-license",
    label: "createLicense",
    group: "clients",
    permission: "ADD_COMPANY",
  },
  { view: "billing", label: "billing", group: "ownCompany", description: "billingHelp" },
  { view: "health", label: "customerHealth", group: "system", phase: "phaseReview", description: "healthHelp" },
  { view: "failures", label: "failures", group: "system", phase: "phaseDetect", description: "failuresHelp" },
  { view: "sync", label: "sync", group: "system", phase: "phaseDiagnose", description: "syncHelp" },
  { view: "fiscal", label: "fiscal", group: "system", phase: "phaseDiagnose", description: "fiscalHelp" },
  {
    view: "outbox",
    label: "outboxRecovery",
    group: "system",
    permission: "MANAGE_OPERATIONS",
    phase: "phaseRecover",
    description: "recoveryHelp",
  },
  { view: "support", label: "supportCenter", group: "system", phase: "phaseSupport", description: "supportHelp" },
  { view: "integrations", label: "integrations", group: "technical", description: "integrationsHelp" },
  { view: "fiscal-policy", label: "verifactuPolicy", group: "technical", description: "fiscalPolicyHelp" },
  { view: "users", label: "users", group: "security" },
  {
    view: "access",
    label: "access",
    group: "security",
    permission: "MANAGE_TENANT_USERS",
  },
  { view: "audit", label: "audit", group: "security" },
];
