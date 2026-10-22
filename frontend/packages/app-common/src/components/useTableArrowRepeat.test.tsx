// @vitest-environment jsdom
import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useTableArrowRepeat, type TableArrowDirection } from "./useTableArrowRepeat";

const arrow = (key: TableArrowDirection, repeat = false) => ({ key, repeat });
const cancellations: Array<[string, () => void]> = [
  ["keyup", () => { window.dispatchEvent(new KeyboardEvent("keyup", { key: "ArrowDown" })); }],
  ["blur", () => { window.dispatchEvent(new Event("blur")); }],
  ["pointerdown", () => { window.dispatchEvent(new Event("pointerdown")); }],
  ["other key", () => { window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); }],
  ["modifier", () => { window.dispatchEvent(new KeyboardEvent("keydown", { key: "Control", ctrlKey: true })); }],
  ["modified arrow", () => { window.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowDown", shiftKey: true })); }],
  ["composition", () => { window.dispatchEvent(new Event("compositionstart")); }],
];

describe("useTableArrowRepeat", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("moves on the first press, then once after 180 ms and every 70 ms", () => {
    const onStep = vi.fn();
    const { result } = renderHook(() => useTableArrowRepeat(onStep));

    act(() => result.current(arrow("ArrowDown")));
    expect(onStep.mock.calls).toEqual([["ArrowDown"]]);
    act(() => vi.advanceTimersByTime(179));
    expect(onStep).toHaveBeenCalledTimes(1);
    act(() => vi.advanceTimersByTime(1));
    expect(onStep).toHaveBeenCalledTimes(2);
    act(() => vi.advanceTimersByTime(69));
    expect(onStep).toHaveBeenCalledTimes(2);
    act(() => vi.advanceTimersByTime(1));
    expect(onStep).toHaveBeenCalledTimes(3);
  });

  it("ignores native repeats, including a repeat without an initial press", () => {
    const onStep = vi.fn();
    const { result } = renderHook(() => useTableArrowRepeat(onStep));

    act(() => result.current(arrow("ArrowDown", true)));
    expect(onStep).not.toHaveBeenCalled();
    act(() => result.current(arrow("ArrowDown")));
    act(() => result.current(arrow("ArrowDown", true)));
    act(() => vi.advanceTimersByTime(180));
    expect(onStep.mock.calls).toEqual([["ArrowDown"], ["ArrowDown"]]);
  });

  it.each(["ctrlKey", "altKey", "metaKey", "shiftKey", "isComposing"] as const)("does not start from an initial arrow with %s", (modifier) => {
    const onStep = vi.fn();
    const { result } = renderHook(() => useTableArrowRepeat(onStep));
    act(() => result.current({ ...arrow("ArrowDown"), [modifier]: true }));
    act(() => vi.advanceTimersByTime(500));
    expect(onStep).not.toHaveBeenCalled();
  });

  it("restarts immediately in the opposite direction", () => {
    const onStep = vi.fn();
    const { result } = renderHook(() => useTableArrowRepeat(onStep));

    act(() => result.current(arrow("ArrowDown")));
    act(() => vi.advanceTimersByTime(100));
    act(() => result.current(arrow("ArrowUp")));
    expect(onStep.mock.calls).toEqual([["ArrowDown"], ["ArrowUp"]]);
    act(() => vi.advanceTimersByTime(80));
    expect(onStep).toHaveBeenCalledTimes(2);
    act(() => vi.advanceTimersByTime(100));
    expect(onStep.mock.calls).toEqual([["ArrowDown"], ["ArrowUp"], ["ArrowUp"]]);
  });

  it.each(cancellations)("cancels on %s without a later ghost step", (_reason, interrupt) => {
    const onStep = vi.fn();
    const { result } = renderHook(() => useTableArrowRepeat(onStep));
    act(() => result.current(arrow("ArrowDown")));
    act(interrupt);
    act(() => vi.advanceTimersByTime(1000));
    expect(onStep).toHaveBeenCalledTimes(1);
  });

  it("cancels when the document becomes hidden", () => {
    const onStep = vi.fn();
    const { result } = renderHook(() => useTableArrowRepeat(onStep));
    act(() => result.current(arrow("ArrowDown")));
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    act(() => document.dispatchEvent(new Event("visibilitychange")));
    act(() => vi.advanceTimersByTime(1000));
    expect(onStep).toHaveBeenCalledTimes(1);
  });

  it("cancels when disabled and after unmount", () => {
    const onStep = vi.fn();
    const { result, rerender, unmount } = renderHook(
      ({ enabled }) => useTableArrowRepeat(onStep, enabled),
      { initialProps: { enabled: true } },
    );
    act(() => result.current(arrow("ArrowDown")));
    rerender({ enabled: false });
    act(() => result.current(arrow("ArrowDown")));
    act(() => vi.advanceTimersByTime(1000));
    expect(onStep).toHaveBeenCalledTimes(1);

    rerender({ enabled: true });
    act(() => result.current(arrow("ArrowDown")));
    unmount();
    act(() => vi.advanceTimersByTime(1000));
    expect(onStep).toHaveBeenCalledTimes(2);
  });

  it("uses the latest callback and stops when it returns false", () => {
    const initialStep = vi.fn();
    const lastStep = vi.fn().mockReturnValue(false);
    const { result, rerender } = renderHook(
      ({ onStep }) => useTableArrowRepeat(onStep),
      { initialProps: { onStep: initialStep as (direction: TableArrowDirection) => boolean | void } },
    );
    act(() => result.current(arrow("ArrowDown")));
    rerender({ onStep: lastStep });
    act(() => vi.advanceTimersByTime(180));
    act(() => vi.advanceTimersByTime(1000));
    act(() => result.current(arrow("ArrowDown", true)));
    expect(initialStep).toHaveBeenCalledTimes(1);
    expect(lastStep).toHaveBeenCalledTimes(1);
    act(() => result.current(arrow("ArrowDown")));
    expect(lastStep).toHaveBeenCalledTimes(2);
    act(() => vi.advanceTimersByTime(1000));
    expect(lastStep).toHaveBeenCalledTimes(2);
  });

  it("schedules the next tick after a slow step instead of catching up", () => {
    const onStep = vi.fn().mockImplementationOnce(() => undefined).mockImplementationOnce(() => {
      vi.setSystemTime(Date.now() + 1000);
    });
    const { result } = renderHook(() => useTableArrowRepeat(onStep));
    act(() => result.current(arrow("ArrowDown")));
    act(() => vi.advanceTimersByTime(180));
    expect(onStep).toHaveBeenCalledTimes(2);
    act(() => vi.advanceTimersByTime(69));
    expect(onStep).toHaveBeenCalledTimes(2);
    act(() => vi.advanceTimersByTime(1));
    expect(onStep).toHaveBeenCalledTimes(3);
  });
});
