import type { ApplicationId } from "./applicationGuideTypes";

export type MarketingAttribution = {
  landingPath: string;
  referrer: string;
  utmSource: string;
  utmMedium: string;
  utmCampaign: string;
};

export type CampaignLocation = { pathname: string; hash?: string; search?: string };

const applications: readonly ApplicationId[] = ["venta", "gestion", "pda", "saas"];
const maximumSearchLength = 4096;
const marketingRoutes = new Set(["inicio", "solucion", "funcionalidades", "apps", "ventajas", "contacto"]);

function boundedSearch(search: string) {
  return typeof search === "string" && search.length <= maximumSearchLength ? new URLSearchParams(search) : null;
}

/** A shareable product choice, not a redirect or an arbitrary application identifier. */
export function parseRequestedApplications(search: string): ApplicationId[] | null {
  const values = boundedSearch(search)?.getAll("apps");
  if (!values || values.length !== 1 || values[0].length > 80) return null;
  const value = values[0].trim();
  if (value === "all") return [...applications];
  const requested = value.split(",").map((app) => app.trim());
  if (requested.length > 8) return null;
  const selected = applications.filter((app) => requested.includes(app));
  return selected.length ? selected : null;
}

/** Only app choices are included: current queries and form values are never propagated. */
export function marketingContactSearch(apps: readonly ApplicationId[]): string {
  const selected = applications.filter((app) => apps.includes(app));
  return selected.length ? `?apps=${selected.length === applications.length ? "all" : selected.join(",")}` : "";
}

function decodedForInspection(value: string) {
  let decoded = value;
  for (let pass = 0; pass < 3; pass += 1) {
    try {
      const next = decodeURIComponent(decoded);
      if (next === decoded) break;
      decoded = next;
    } catch {
      break;
    }
  }
  return decoded.normalize("NFKC");
}

// This deliberately drops obvious addresses, phone numbers and credentials. It is
// a conservative filter, not a claim that arbitrary campaign text is anonymous.
function containsObviousPersonalOrSecretData(value: string) {
  const inspected = decodedForInspection(value);
  return /[\u0000-\u001f\u007f@<>\\]/u.test(inspected)
    || /\d{7,}/u.test(inspected)
    || /(?:\+\d[\d ().-]{7,}\d)/u.test(inspected)
    || /\b\d{3}[ .()-]+\d{3}[ .()-]+\d{3}\b/u.test(inspected)
    || /(?:^|[^A-Za-z0-9])(?:sk[-_]|gh[pousr]_|github_pat_|glpat-|xox[baprs]-|wxid_)[\w-]+/iu.test(inspected)
    || /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/u.test(inspected)
    || /\b(?:access[_. -]?token|refresh[_. -]?token|auth[_. -]?token|api[_. -]?key|session[_. -]?id|password|passwd|authorization|bearer|secret)(?:\b|_)/iu.test(inspected)
    || /\b(?:email|e-mail|phone|telephone|telefono|teléfono|mobile)[=:]/iu.test(inspected)
    || /\b[A-Fa-f0-9]{24,}\b/u.test(inspected)
    || /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/iu.test(inspected)
    || /\b(?=[A-Za-z0-9_-]{40,}\b)(?=[A-Za-z0-9_-]*\d)[A-Za-z0-9_-]+\b/u.test(inspected);
}

function campaignValue(value: unknown, maximumLength: number) {
  if (typeof value !== "string" || value.length > maximumSearchLength) return "";
  const normalized = value.normalize("NFKC").trim();
  if (containsObviousPersonalOrSecretData(normalized) || !/^[\p{L}\p{N} ._~-]*$/u.test(normalized)) return "";
  return normalized.slice(0, maximumLength).trim();
}

function pathOnly(value: string, maximumLength: number): string | null {
  if (value.length > maximumSearchLength || !value.startsWith("/") || value.startsWith("//")) return null;
  const path = value.split(/[?#]/u, 1)[0];
  if (containsObviousPersonalOrSecretData(path) || /[;:]/u.test(decodedForInspection(path))) return null;
  try {
    const parsed = new URL(path, "https://marketing.invalid");
    if (parsed.origin !== "https://marketing.invalid" || parsed.pathname.length > maximumLength) return null;
    return parsed.pathname;
  } catch {
    return null;
  }
}

function marketingHash(value = "") {
  if (value.length > maximumSearchLength) return "";
  const route = value.replace(/^#/u, "").split(/[?#]/u, 1)[0].replace(/\/+$/u, "");
  const match = /^\/producto\/([^/]+)$/u.exec(route);
  return match && marketingRoutes.has(match[1]) ? `#${route}` : "";
}

function currentLandingPath(location: CampaignLocation) {
  return `${pathOnly(location.pathname, 460) ?? "/"}${marketingHash(location.hash)}`;
}

function previousLandingPath(value: unknown) {
  if (typeof value !== "string" || value.length > maximumSearchLength) return "";
  const fragment = value.indexOf("#");
  const path = pathOnly(fragment < 0 ? value : value.slice(0, fragment), 460);
  return path ? `${path}${marketingHash(fragment < 0 ? "" : value.slice(fragment))}` : "";
}

function safeReferrer(value: unknown) {
  if (typeof value !== "string" || !value || value.length > maximumSearchLength) return "";
  try {
    const url = new URL(value);
    if (!["http:", "https:"].includes(url.protocol) || containsObviousPersonalOrSecretData(url.hostname)) return "";
    // Credentials, query parameters and fragments are intentionally discarded.
    const path = pathOnly(url.pathname, 460) ?? "/";
    const referrer = `${url.origin}${path}`;
    return referrer.length <= 512 ? referrer : `${url.origin}/`.slice(0, 512);
  } catch {
    return "";
  }
}

function previousField(previous: unknown, key: keyof MarketingAttribution): unknown {
  if (!previous || typeof previous !== "object" || Array.isArray(previous)) return undefined;
  try {
    return Object.hasOwn(previous, key) ? (previous as Record<string, unknown>)[key] : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Existing form attribution only: no cookies, storage access, network requests or
 * analytics events. Callers may persist this bounded result, never raw URL data.
 */
export function createCampaignAttribution(location: CampaignLocation, referrer: string, previous?: unknown): MarketingAttribution {
  const params = boundedSearch(location.search ?? "");
  const current = (key: string, maximumLength: number) => {
    const values = params?.getAll(key);
    return values?.length === 1 ? campaignValue(values[0], maximumLength) : "";
  };
  return {
    landingPath: previousLandingPath(previousField(previous, "landingPath")) || currentLandingPath(location),
    referrer: safeReferrer(previousField(previous, "referrer")) || safeReferrer(referrer),
    utmSource: current("utm_source", 100) || campaignValue(previousField(previous, "utmSource"), 100),
    utmMedium: current("utm_medium", 100) || campaignValue(previousField(previous, "utmMedium"), 100),
    utmCampaign: current("utm_campaign", 160) || campaignValue(previousField(previous, "utmCampaign"), 160)
  };
}
