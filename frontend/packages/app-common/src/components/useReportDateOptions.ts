import { useCallback, useEffect, useState } from "react";
import { apiRequest } from "../api/client";
import { isValidReportDate } from "./ReportDateRangeFilter";

export function useReportDateOptions(report: string, token: string | undefined, enabled = true) {
  const [dateOptions, setDateOptions] = useState<{ currentDate: string; earliestDate: string } | null>(null);
  const [dateOptionsFailed, setDateOptionsFailed] = useState(false);
  const [reload, setReload] = useState(0);
  const retryDateOptions = useCallback(() => setReload((value) => value + 1), []);
  useEffect(() => {
    setDateOptions(null);
    setDateOptionsFailed(false);
    if (!enabled || !token) return;
    let cancelled = false;
    void apiRequest<{ currentDate: string; earliestDate: string }>(
      `/document-reports/date-options?report=${encodeURIComponent(report)}`, { token }
    ).then((value) => {
      if (!isValidReportDate(value.currentDate) || !isValidReportDate(value.earliestDate)
          || value.earliestDate > value.currentDate) throw new Error("Invalid report date options");
      if (!cancelled) setDateOptions(value);
    }).catch(() => { if (!cancelled) setDateOptionsFailed(true); });
    return () => { cancelled = true; };
  }, [report, token, enabled, reload]);
  return { dateOptions, dateOptionsFailed, retryDateOptions };
}
