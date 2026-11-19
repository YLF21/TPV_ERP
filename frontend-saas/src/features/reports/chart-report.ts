type Page<Row> = { page: number; size: number; rows: Row[] };

const pageSize = 100;
export const chartGroupLimit = 400;

export async function loadChartReport<Row, Report extends Page<Row>>(
  load: (page: number, size: number) => Promise<Report>,
  total: (report: Report) => number,
  rowKey: (row: Report["rows"][number]) => string,
): Promise<{ report: Report | null; limitReached: boolean }> {
  const first = await load(0, pageSize);
  const count = total(first);
  if (count > chartGroupLimit) return { report: null, limitReached: true };
  if (first.page !== 0 || first.size !== pageSize || first.rows.length !== Math.min(count, pageSize)) {
    return { report: null, limitReached: false };
  }

  const rows = [...first.rows];
  const pages = Math.ceil(count / pageSize);
  for (let page = 1; page < pages; page++) {
    const next = await load(page, pageSize);
    if (total(next) !== count || next.page !== page || next.size !== pageSize ||
        next.rows.length !== Math.min(pageSize, count - page * pageSize)) {
      return { report: null, limitReached: false };
    }
    rows.push(...next.rows);
  }
  // A row moving between pages during a refresh must never be counted twice.
  if (new Set(rows.map(rowKey)).size !== count) {
    return { report: null, limitReached: false };
  }
  return { report: { ...first, rows }, limitReached: false };
}
