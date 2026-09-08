import { useEffect, useRef, type RefObject } from "react";

/** A separate header viewport reserves real space above the scrolling rows. */
export function useExcelTableHeader(body: RefObject<HTMLDivElement | null>, revision: unknown) {
  const header = useRef<HTMLDivElement>(null);
  function syncHeader() {
    if (!header.current || !body.current) return;
    header.current.scrollLeft = body.current.scrollLeft;
    header.current.style.marginRight = `${body.current.offsetWidth - body.current.clientWidth}px`;
  }
  function syncBody() {
    if (header.current && body.current) body.current.scrollLeft = header.current.scrollLeft;
  }
  useEffect(() => {
    syncHeader();
    if (!body.current || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(syncHeader);
    observer.observe(body.current);
    return () => observer.disconnect();
  }, [body, revision]);
  return { header, syncHeader, syncBody };
}

/** Windowed rows are located by their source index, not by a mounted DOM node. */
export function revealExcelTableRow(viewport: HTMLDivElement | null, index: number, rowHeight: number) {
  if (!viewport || index < 0) return 0;
  const top = index * rowHeight;
  if (top < viewport.scrollTop) viewport.scrollTop = top;
  else if (top + rowHeight > viewport.scrollTop + viewport.clientHeight) {
    viewport.scrollTop = Math.max(0, top + rowHeight - viewport.clientHeight);
  }
  return viewport.scrollTop;
}
