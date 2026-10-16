export const demoRequestStatuses = Object.freeze(["NEW", "CONTACTED", "CLOSED"]);

export function demoRequestPagePath({ page = 0, size = 20, status, query = "" } = {}) {
  const normalizedQuery = query.trim();
  if (!Number.isInteger(page) || page < 0) throw new RangeError("Invalid demo request page");
  if (!Number.isInteger(size) || size < 1 || size > 100) throw new RangeError("Invalid demo request page size");
  if (status && !demoRequestStatuses.includes(status)) throw new RangeError("Invalid demo request status");
  if (normalizedQuery.length > 160) throw new RangeError("Demo request search exceeds 160 characters");
  const parameters = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) parameters.set("status", status);
  if (normalizedQuery) parameters.set("query", normalizedQuery);
  return `/api/v1/admin/demo-requests/page?${parameters}`;
}
