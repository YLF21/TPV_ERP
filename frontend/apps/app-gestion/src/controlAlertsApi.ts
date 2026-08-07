import { apiRequest } from "@tpverp/app-common";

export const controlAlertTypes = [
  "SALE_SCREEN_CLEARED",
  "CONSECUTIVE_LINE_DELETIONS",
  "MANUAL_PRICE_CHANGE_OVER_PERCENT",
  "MANUAL_PRICE_CHANGED",
  "MANUAL_DISCOUNT_OVER_PERCENT",
  "PRODUCT_DISCOUNT_APPLIED",
  "TICKET_CANCELLED",
  "INACTIVE_PRODUCT_SOLD",
  "MANUAL_NEGATIVE_QUANTITY",
  "REFUND_POLICY_OVERRIDE",
  "CASH_DRAWER_OPENED",
  "PRODUCT_CATALOG_MODIFIED"
] as const;

export const controlAlertStatuses = ["NEW", "REVIEWED", "CLOSED", "DISMISSED"] as const;
export const controlAlertPriorities = ["INFORMATIONAL", "MEDIUM", "HIGH", "CRITICAL"] as const;

export type ControlAlertType = typeof controlAlertTypes[number];
export type ControlAlertStatus = typeof controlAlertStatuses[number];
export type ControlAlertPriority = typeof controlAlertPriorities[number];
export type ControlAlertTransition = "REVIEW" | "CLOSE" | "DISMISS";
export type ControlRuleParameterKind = "NONE" | "QUANTITY" | "PERCENTAGE";

export type ControlAlert = {
  id: string;
  type: ControlAlertType;
  status: ControlAlertStatus;
  occurredAt: string;
  documentId?: string | null;
  documentNumber?: string | null;
  ruleId?: string | null;
  ruleVersion?: number | null;
  ruleName?: string | null;
  terminalId?: string | null;
  userId?: string | null;
  userName?: string | null;
  data?: Record<string, unknown> | null;
  priority: ControlAlertPriority;
  assigneeId?: string | null;
  dueAt?: string | null;
  updatedAt?: string | null;
  history?: ControlAlertHistoryEntry[];
  workHistory?: ControlAlertWorkHistoryEntry[];
  version: number;
};

export type ControlAlertHistoryEntry = {
  previousStatus?: ControlAlertStatus | null;
  newStatus: ControlAlertStatus;
  comment?: string | null;
  changedBy?: string | null;
  changedAt: string;
};

export type ControlAlertPage = {
  items: ControlAlert[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

type SpringPage<T> = {
  content: T[];
  number: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

type ControlAlertDetailResponse = {
  alert: ControlAlert;
  history: ControlAlertHistoryEntry[];
  workHistory: ControlAlertWorkHistoryEntry[];
};

export type ControlAlertFilters = {
  search: string;
  status: "" | ControlAlertStatus;
  type?: "" | ControlAlertType;
  ruleId?: string;
  priority?: "" | ControlAlertPriority;
  assigneeId?: string;
  overdue?: boolean;
  from?: string;
  to?: string;
  page: number;
  size: number;
  sortBy?: string;
  sortDirection?: "asc" | "desc";
};

export type RelatedDocument = {
  id: string;
  type: string;
  number: string;
  status: string;
  date: string;
  customerId?: string | null;
  supplierId?: string | null;
  globalDiscount: number;
  lines: Array<{
    position: number;
    lineType: string;
    productId?: string | null;
    code?: string | null;
    name: string;
    quantity: number;
    unitPrice: number;
    discount: number;
    taxesIncluded: boolean;
    taxRegime: string;
    taxPercent: number;
    base: number;
    tax: number;
    total: number;
  }>;
  baseTotal: number;
  taxTotal: number;
  total: number;
  currency: string;
  cancellationReason?: string | null;
  payments: Array<{
    position: number;
    paymentMethodId?: string | null;
    paymentMethod: string;
    amount: number;
    principal: boolean;
    tendered?: number | null;
    change?: number | null;
    reference?: string | null;
    cardMode?: string | null;
    terminalStatus?: string | null;
  }>;
};

export type ControlRule = {
  id: string;
  type: ControlAlertType;
  active: boolean;
  name: string;
  configuration: Record<string, unknown>;
  ruleVersion: number;
  createdBy?: string | null;
  updatedBy?: string | null;
  version: number;
  createdAt?: string | null;
  updatedAt?: string | null;
};

export type ControlRuleDraft = {
  type: ControlAlertType;
  active: boolean;
  configuration: Record<string, unknown>;
};

export type ControlRuleCatalogItem = {
  type: ControlAlertType;
  name: string;
  parameterKind: ControlRuleParameterKind;
  defaultConfiguration: Record<string, unknown>;
  supported: boolean;
  configured: boolean;
  ruleId?: string | null;
};

export type ControlRuleAlertGroup = {
  ruleId: string;
  type: ControlAlertType;
  ruleName: string;
  active: boolean;
  supported: boolean;
  total: number;
  newCount: number;
  reviewedCount: number;
  closedCount: number;
  dismissedCount: number;
  parameterKind: ControlRuleParameterKind;
  configuration: Record<string, unknown>;
};

export type ControlAlertWorkHistoryEntry = {
  previousPriority: ControlAlertPriority;
  newPriority: ControlAlertPriority;
  previousAssigneeId?: string | null;
  newAssigneeId?: string | null;
  previousDueAt?: string | null;
  newDueAt?: string | null;
  comment?: string | null;
  changedBy?: string | null;
  changedAt: string;
};

export type ControlAlertAssignee = { id: string; name: string; userName: string };

export type ControlAlertMetric = {
  key: string;
  label?: string | null;
  count: number;
};

export type ControlAlertsAnalytics = {
  total: number;
  overdueCount: number;
  byStatus: ControlAlertMetric[];
  byType: ControlAlertMetric[];
  byUser: ControlAlertMetric[];
  byTerminal: ControlAlertMetric[];
};

function queryString(filters: ControlAlertFilters): string {
  const params = new URLSearchParams({
    page: String(filters.page),
    size: String(filters.size)
  });
  if (filters.search.trim()) params.set("search", filters.search.trim());
  if (filters.status) params.set("status", filters.status);
  if (filters.type) params.set("type", filters.type);
  if (filters.ruleId) params.set("ruleId", filters.ruleId);
  if (filters.priority) params.set("priority", filters.priority);
  if (filters.assigneeId) params.set("assigneeId", filters.assigneeId);
  if (filters.overdue) params.set("overdue", "true");
  if (filters.from) params.set("from", filters.from);
  if (filters.to) params.set("to", filters.to);
  if (filters.sortBy) params.set("sortBy", filters.sortBy);
  if (filters.sortDirection) params.set("sortDirection", filters.sortDirection);
  return params.toString();
}

export async function loadControlAlerts(filters: ControlAlertFilters, token?: string): Promise<ControlAlertPage> {
  const page = await apiRequest<SpringPage<ControlAlert>>(`/control/alerts?${queryString(filters)}`, { token });
  return {
    items: page.content,
    page: page.number,
    size: page.size,
    totalElements: page.totalElements,
    totalPages: page.totalPages
  };
}

export async function loadControlAlert(id: string, token?: string): Promise<ControlAlert> {
  const detail = await apiRequest<ControlAlertDetailResponse>(`/control/alerts/${encodeURIComponent(id)}`, { token });
  return { ...detail.alert, history: detail.history, workHistory: detail.workHistory };
}

export async function transitionControlAlert(
  id: string,
  action: ControlAlertTransition,
  comment: string,
  version: number,
  token?: string
): Promise<ControlAlert> {
  const detail = await apiRequest<ControlAlertDetailResponse>(`/control/alerts/${encodeURIComponent(id)}/${action.toLowerCase()}`, {
    method: "POST",
    token,
    body: { comment: comment.trim() || null, version }
  });
  return { ...detail.alert, history: detail.history, workHistory: detail.workHistory };
}

export function loadControlAlertAssignees(token?: string) {
  return apiRequest<ControlAlertAssignee[]>("/control/alerts/assignees", { token });
}

export async function updateControlAlertWork(
  alert: ControlAlert,
  work: { priority: ControlAlertPriority; assigneeId?: string | null; dueAt?: string | null; comment?: string },
  token?: string
): Promise<ControlAlert> {
  const detail = await apiRequest<ControlAlertDetailResponse>(`/control/alerts/${encodeURIComponent(alert.id)}/work`, {
    method: "PUT",
    token,
    body: { ...work, comment: work.comment?.trim() || null, version: alert.version }
  });
  return { ...detail.alert, history: detail.history, workHistory: detail.workHistory };
}

export function loadRelatedDocument(id: string, token?: string) {
  return apiRequest<RelatedDocument>(`/control/alerts/${encodeURIComponent(id)}/document`, { token });
}

export function loadControlRules(token?: string) {
  return apiRequest<ControlRule[]>("/control/rules", { token });
}

export function loadControlRuleCatalog(token?: string) {
  return apiRequest<ControlRuleCatalogItem[]>("/control/rules/catalog", { token });
}

export function loadControlAlertGroups(from: string, to: string, token?: string) {
  const params = new URLSearchParams({ from, to });
  return apiRequest<ControlRuleAlertGroup[]>(`/control/alerts/groups?${params}`, { token });
}

export function loadControlAlertsAnalytics(from: string, to: string, overdueHours: number, token?: string) {
  const params = new URLSearchParams({ from, to, overdueHours: String(overdueHours) });
  return apiRequest<ControlAlertsAnalytics>(`/control/alerts/analytics?${params}`, { token });
}

export function saveControlRule(draft: ControlRuleDraft, existing: ControlRule | null, token?: string) {
  const body = existing
    ? { active: draft.active, configuration: draft.configuration, version: existing.version }
    : { type: draft.type, active: draft.active, configuration: draft.configuration };
  return apiRequest<ControlRule>(existing
    ? `/control/rules/${encodeURIComponent(existing.id)}`
    : "/control/rules", {
    method: existing ? "PUT" : "POST",
    token,
    body
  });
}

export function setControlRuleActive(rule: ControlRule, active: boolean, token?: string) {
  return apiRequest<ControlRule>(`/control/rules/${encodeURIComponent(rule.id)}`, {
    method: "PUT",
    token,
    body: { active, configuration: rule.configuration, version: rule.version }
  });
}
