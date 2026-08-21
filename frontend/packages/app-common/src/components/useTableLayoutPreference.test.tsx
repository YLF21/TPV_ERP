// @vitest-environment jsdom
import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  loadTablePreference,
  saveTablePreference,
  writeStoredTableLayout
} from "./tableLayoutPreferences";
import { useTableLayoutPreference } from "./useTableLayoutPreference";

vi.mock("./tableLayoutPreferences", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./tableLayoutPreferences")>();
  return {
    ...actual,
    loadTablePreference: vi.fn(),
    saveTablePreference: vi.fn()
  };
});

const loadTablePreferenceMock = vi.mocked(loadTablePreference);
const saveTablePreferenceMock = vi.mocked(saveTablePreference);

describe("useTableLayoutPreference", () => {
  beforeEach(() => {
    localStorage.clear();
    loadTablePreferenceMock.mockReset();
    saveTablePreferenceMock.mockReset();
  });

  it("flushes the latest pending layout when the screen is unmounted", async () => {
    loadTablePreferenceMock.mockResolvedValue({
      app: "venta",
      tableKey: "reports.salesReport.tickets",
      columns: [
        { key: "ticket", width: 152, visible: true },
        { key: "memberBalance", width: 128, visible: false },
        { key: "total", width: 120, visible: true }
      ],
      updatedAt: "2026-08-21T12:00:00Z"
    });
    saveTablePreferenceMock.mockResolvedValue({
      app: "venta",
      tableKey: "reports.salesReport.tickets",
      columns: [],
      updatedAt: "2026-08-21T12:01:00Z"
    });

    const { result, unmount } = renderHook(() => useTableLayoutPreference({
      app: "venta",
      username: "ADMIN",
      accessToken: "token",
      tableKey: "reports.salesReport.tickets",
      definitions: [
        { key: "ticket", defaultWidth: 152, defaultVisible: true },
        { key: "memberBalance", defaultWidth: 128, defaultVisible: false },
        { key: "total", defaultWidth: 120, defaultVisible: true }
      ],
      debounceMs: 60_000
    }));

    await waitFor(() => expect(result.current.ready).toBe(true));
    act(() => result.current.toggleColumnVisibility("memberBalance"));
    expect(saveTablePreferenceMock).not.toHaveBeenCalled();

    unmount();

    await waitFor(() => expect(saveTablePreferenceMock).toHaveBeenCalledWith(
      "venta",
      "reports.salesReport.tickets",
      expect.arrayContaining([
        expect.objectContaining({ key: "memberBalance", visible: true })
      ]),
      "token"
    ));
  });

  it("flushes the previous table before loading a different table identity", async () => {
    loadTablePreferenceMock.mockResolvedValue({
      app: "venta",
      tableKey: "reports.salesReport.tickets",
      columns: [
        { key: "ticket", width: 152, visible: true },
        { key: "memberBalance", width: 128, visible: false },
        { key: "total", width: 120, visible: true }
      ],
      updatedAt: "2026-08-21T12:00:00Z"
    });
    saveTablePreferenceMock.mockResolvedValue({
      app: "venta",
      tableKey: "reports.salesReport.tickets",
      columns: [],
      updatedAt: "2026-08-21T12:01:00Z"
    });
    const definitions = [
      { key: "ticket", defaultWidth: 152, defaultVisible: true },
      { key: "memberBalance", defaultWidth: 128, defaultVisible: false },
      { key: "total", defaultWidth: 120, defaultVisible: true }
    ];
    const { result, rerender } = renderHook(
      ({ tableKey }) => useTableLayoutPreference({
        app: "venta",
        username: "ADMIN",
        accessToken: "token",
        tableKey,
        definitions,
        debounceMs: 60_000
      }),
      { initialProps: { tableKey: "reports.salesReport.tickets" } }
    );

    await waitFor(() => expect(result.current.ready).toBe(true));
    act(() => result.current.toggleColumnVisibility("memberBalance"));
    rerender({ tableKey: "reports.salesReport.invoices" });

    await waitFor(() => expect(saveTablePreferenceMock).toHaveBeenCalledWith(
      "venta",
      "reports.salesReport.tickets",
      expect.arrayContaining([
        expect.objectContaining({ key: "memberBalance", visible: true })
      ]),
      "token"
    ));
  });

  it("keeps and re-saves a local layout newer than the backend response", async () => {
    const definitions = [
      { key: "ticket", defaultWidth: 152, defaultVisible: true },
      { key: "memberBalance", defaultWidth: 128, defaultVisible: false },
      { key: "total", defaultWidth: 120, defaultVisible: true }
    ];
    writeStoredTableLayout(
      "venta",
      "ADMIN",
      "reports.salesReport.tickets",
      [
        { key: "ticket", width: 152, visible: true },
        { key: "memberBalance", width: 128, visible: true },
        { key: "total", width: 120, visible: true }
      ],
      localStorage,
      "2026-08-21T12:02:00Z"
    );
    loadTablePreferenceMock.mockResolvedValue({
      app: "venta",
      tableKey: "reports.salesReport.tickets",
      columns: [
        { key: "ticket", width: 152, visible: true },
        { key: "memberBalance", width: 128, visible: false },
        { key: "total", width: 120, visible: true }
      ],
      updatedAt: "2026-08-21T12:01:00Z"
    });
    saveTablePreferenceMock.mockResolvedValue({
      app: "venta",
      tableKey: "reports.salesReport.tickets",
      columns: [],
      updatedAt: "2026-08-21T12:03:00Z"
    });

    const { result } = renderHook(() => useTableLayoutPreference({
      app: "venta",
      username: "ADMIN",
      accessToken: "token",
      tableKey: "reports.salesReport.tickets",
      definitions
    }));

    await waitFor(() => expect(result.current.ready).toBe(true));
    expect(result.current.layout.find((column) => column.key === "memberBalance")?.visible)
      .toBe(true);
    await waitFor(() => expect(saveTablePreferenceMock).toHaveBeenCalledWith(
      "venta",
      "reports.salesReport.tickets",
      expect.arrayContaining([
        expect.objectContaining({ key: "memberBalance", visible: true })
      ]),
      "token"
    ));
  });

  it("applies a backend layout newer than the stored local layout", async () => {
    const definitions = [
      { key: "ticket", defaultWidth: 152, defaultVisible: true },
      { key: "memberBalance", defaultWidth: 128, defaultVisible: false },
      { key: "total", defaultWidth: 120, defaultVisible: true }
    ];
    writeStoredTableLayout(
      "venta",
      "ADMIN",
      "reports.salesReport.tickets",
      [
        { key: "ticket", width: 152, visible: true },
        { key: "memberBalance", width: 128, visible: false },
        { key: "total", width: 120, visible: true }
      ],
      localStorage,
      "2026-08-21T12:01:00Z"
    );
    loadTablePreferenceMock.mockResolvedValue({
      app: "venta",
      tableKey: "reports.salesReport.tickets",
      columns: [
        { key: "ticket", width: 152, visible: true },
        { key: "memberBalance", width: 128, visible: true },
        { key: "total", width: 120, visible: true }
      ],
      updatedAt: "2026-08-21T12:02:00Z"
    });

    const { result } = renderHook(() => useTableLayoutPreference({
      app: "venta",
      username: "ADMIN",
      accessToken: "token",
      tableKey: "reports.salesReport.tickets",
      definitions
    }));

    await waitFor(() => expect(result.current.ready).toBe(true));
    expect(result.current.layout.find((column) => column.key === "memberBalance")?.visible)
      .toBe(true);
    expect(saveTablePreferenceMock).not.toHaveBeenCalled();
  });
});
