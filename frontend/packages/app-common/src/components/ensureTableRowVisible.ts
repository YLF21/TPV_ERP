/** Reveal a row inside its table without scrolling the page or changing columns. */
export function ensureTableRowVisible(container: HTMLElement, row: HTMLElement) {
  if (container.clientHeight <= 0 || !container.contains(row)) return;
  const viewport = container.getBoundingClientRect();
  // clientHeight excludes the horizontal scrollbar; the outer rectangle includes it.
  const viewportTop = viewport.top + container.clientTop;
  const viewportBottom = viewportTop + container.clientHeight;
  const headers = container.querySelectorAll<HTMLElement>(
    'thead, thead th, [role="columnheader"], [role="row"].head, [role="row"].header, .stock-row-head, .sale-row-head, .sale-customer-table-header, .warehouse-supplier-table-header, .sales-document-customer-columns',
  );
  const visibleTop = Math.min(viewportBottom, Array.from(headers).reduce(
    (top, header) => Math.max(top, header.getBoundingClientRect().bottom), viewportTop,
  ));
  const bounds = row.getBoundingClientRect();
  const delta = bounds.top < visibleTop ? bounds.top - visibleTop
    : bounds.bottom > viewportBottom ? bounds.bottom - viewportBottom : 0;
  if (delta !== 0) {
    container.scrollTop = Math.max(0, Math.min(
      container.scrollHeight - container.clientHeight, container.scrollTop + delta,
    ));
  }
}
