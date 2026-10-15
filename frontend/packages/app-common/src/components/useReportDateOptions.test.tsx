// @vitest-environment jsdom

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useReportDateOptions } from "./useReportDateOptions";

const apiRequestMock = vi.hoisted(() => vi.fn());

vi.mock("../api/client", () => ({ apiRequest: apiRequestMock }));

const validOptions = { currentDate: "2026-10-15", earliestDate: "2025-01-01" };

describe("useReportDateOptions", () => {
  beforeEach(() => apiRequestMock.mockReset());
  afterEach(() => cleanup());

  it("does not request date options without a token or while disabled", () => {
    const { rerender } = renderHook(
      ({ token, enabled }: { token?: string; enabled: boolean }) => useReportDateOptions("inputInvoices", token, enabled),
      { initialProps: { token: undefined as string | undefined, enabled: true } }
    );

    rerender({ token: "token", enabled: false });
    expect(apiRequestMock).not.toHaveBeenCalled();
  });

  it("recovers from a failed request when retried", async () => {
    apiRequestMock.mockRejectedValueOnce(new Error("network error")).mockResolvedValueOnce(validOptions);
    const { result } = renderHook(() => useReportDateOptions("inputInvoices", "token"));

    await waitFor(() => expect(result.current.dateOptionsFailed).toBe(true));
    act(() => result.current.retryDateOptions());
    await waitFor(() => expect(result.current.dateOptions).toEqual(validOptions));
    expect(result.current.dateOptionsFailed).toBe(false);
    expect(apiRequestMock).toHaveBeenCalledTimes(2);
    expect(apiRequestMock).toHaveBeenNthCalledWith(1,
      "/document-reports/date-options?report=inputInvoices", { token: "token" });
  });

  it("rejects invalid metadata and recovers when retried", async () => {
    apiRequestMock.mockResolvedValueOnce({ currentDate: "not-a-date", earliestDate: "2025-01-01" })
      .mockResolvedValueOnce(validOptions);
    const { result } = renderHook(() => useReportDateOptions("inputDeliveryNotes", "token"));

    await waitFor(() => expect(result.current.dateOptionsFailed).toBe(true));
    act(() => result.current.retryDateOptions());
    await waitFor(() => expect(result.current.dateOptions).toEqual(validOptions));
    expect(apiRequestMock).toHaveBeenCalledTimes(2);
  });

  it("ignores a pending response from the previous report", async () => {
    let resolveOldRequest!: (value: typeof validOptions) => void;
    const oldRequest = new Promise<typeof validOptions>((resolve) => { resolveOldRequest = resolve; });
    apiRequestMock.mockImplementation((path: string) => String(path).includes("report=oldReport")
      ? oldRequest
      : Promise.resolve({ currentDate: "2026-09-30", earliestDate: "2024-01-01" }));
    const { result, rerender } = renderHook(
      ({ report }: { report: string }) => useReportDateOptions(report, "token"),
      { initialProps: { report: "oldReport" } }
    );

    rerender({ report: "newReport" });
    await waitFor(() => expect(result.current.dateOptions).toEqual({
      currentDate: "2026-09-30", earliestDate: "2024-01-01"
    }));
    await act(async () => { resolveOldRequest(validOptions); await oldRequest; });

    expect(result.current.dateOptions).toEqual({
      currentDate: "2026-09-30", earliestDate: "2024-01-01"
    });
    expect(apiRequestMock).toHaveBeenNthCalledWith(1,
      "/document-reports/date-options?report=oldReport", { token: "token" });
    expect(apiRequestMock).toHaveBeenNthCalledWith(2,
      "/document-reports/date-options?report=newReport", { token: "token" });
  });
});
