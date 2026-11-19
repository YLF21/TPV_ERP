import { useEffect, useState } from "react";
import { useRemote } from "../../app/RefreshContext";
import { request } from "../../lib/api";
import type { Credentials } from "../../lib/types";
import { reportQuery } from "./report-query";

type PageSize = { page: number; size: number };
const rememberedPages = new Map<string, number>();

export function useReportPage<T extends PageSize>(credentials: Credentials, path: string, query: URLSearchParams,
  total: (data: T) => number, enabled = true) {
  const scope = `${credentials.accessToken}:${path}?${query}`;
  const positionKey = `${credentials.mode}:${credentials.username}:${credentials.companyId ?? ""}:${path}?${query}`;
  const [position, setPosition] = useState(() => {
    const linked = new URLSearchParams(window.location.hash.split("?")[1] ?? "").get("page") ?? "";
    const explicit = /\/(articles|documents)$/.test(path) && /^\d{1,7}$/.test(linked) ? Number(linked) : undefined;
    return { scope, page: explicit ?? rememberedPages.get(positionKey) ?? 0 };
  });
  const [lastResult, setLastResult] = useState<{ scope: string; data: T } | null>(null);
  const page = position.scope === scope ? position.page : 0;
  const url = `${path}?${reportQuery(query, page)}`;
  const remote = useRemote(() => enabled ? request<T>(credentials, url) : Promise.resolve(null),
    [credentials.accessToken, url, enabled]);
  const result = remote.data ?? (enabled && remote.loading && lastResult?.scope === scope ? lastResult.data : null);
  useEffect(() => {
    if (!remote.data) return;
    const last = Math.max(0, Math.ceil(total(remote.data) / remote.data.size) - 1);
    if (page > last) { rememberedPages.set(positionKey, last); setPosition({ scope, page: last }); }
    setLastResult({ scope, data: remote.data });
  }, [remote.data, scope, page]);
  return {
    ...remote, result, page,
    setPage: (next: number) => {
      rememberedPages.set(positionKey, Math.max(0, next));
      if (rememberedPages.size > 100) rememberedPages.delete(rememberedPages.keys().next().value!);
      setPosition({ scope, page: Math.max(0, next) });
    },
  };
}
