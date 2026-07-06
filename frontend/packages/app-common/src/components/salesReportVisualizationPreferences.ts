import { apiRequest } from "../api/client";
import type { AppKind } from "../types";

export type ReportDefinition = {
  availableAttributes: string[];
  defaultVisibleAttributes: string[];
};

export type ReportVisualizationPreference = {
  reportKey: string;
  visibleAttributes: string[];
};

type PreferenceListResponse = {
  preferences: ReportVisualizationPreference[];
};

export function sanitizeVisibleAttributes(
  savedAttributes: string[],
  report: ReportDefinition
): string[] {
  const available = new Set(report.availableAttributes);
  const requiredTotal = available.has("total");
  const seen = new Set<string>();
  const sanitized: string[] = [];

  for (const attribute of savedAttributes) {
    if (attribute === "total" || !available.has(attribute) || seen.has(attribute)) {
      continue;
    }
    seen.add(attribute);
    sanitized.push(attribute);
  }

  if (sanitized.length === 0) {
    return [...report.defaultVisibleAttributes];
  }

  if (requiredTotal) {
    sanitized.push("total");
  }

  return sanitized;
}

export function applySavedVisualizationPreferences(
  current: Record<string, string[]>,
  reports: Record<string, ReportDefinition>,
  preferences: ReportVisualizationPreference[]
): Record<string, string[]> {
  return preferences.reduce((next, preference) => {
    const report = reports[preference.reportKey];
    if (!report) {
      return next;
    }
    return {
      ...next,
      [preference.reportKey]: sanitizeVisibleAttributes(preference.visibleAttributes, report)
    };
  }, { ...current });
}

export async function loadReportVisualizationPreferences(
  app: AppKind,
  token?: string
): Promise<ReportVisualizationPreference[]> {
  if (!token) {
    return [];
  }
  const response = await apiRequest<PreferenceListResponse>(
    `/sales-reports/visualization-preferences?app=${encodeURIComponent(app)}`,
    { token }
  );
  return response.preferences;
}

export async function saveReportVisualizationPreference(
  app: AppKind,
  token: string | undefined,
  reportKey: string,
  visibleAttributes: string[]
): Promise<void> {
  if (!token || visibleAttributes.length === 0) {
    return;
  }
  await apiRequest<ReportVisualizationPreference>(
    `/sales-reports/visualization-preferences/${encodeURIComponent(reportKey)}`,
    {
      method: "PUT",
      token,
      body: {
        app,
        visibleAttributes
      }
    }
  );
}
