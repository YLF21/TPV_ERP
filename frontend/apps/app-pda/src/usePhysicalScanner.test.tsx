// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  PhysicalScannerStatus,
  ScannerBurstDetector,
  isAccidentalDuplicate,
  usePhysicalScanner
} from "./usePhysicalScanner";

function dispatchKey(target: EventTarget, key: string, timestamp: number) {
  const event = new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true });
  Object.defineProperty(event, "timeStamp", { value: timestamp });
  target.dispatchEvent(event);
  return event;
}

function scan(target: EventTarget, value: string, start = 100) {
  [...value].forEach((key, index) => dispatchKey(target, key, start + index * 12));
  return dispatchKey(target, "Enter", start + value.length * 12);
}

function Harness({ onScan }: { onScan: (value: string) => Promise<boolean> | boolean }) {
  const scanner = usePhysicalScanner({ enabled: true, locale: "es", onScan, duplicateWindowMs: 1500 });
  return <>
    <input aria-label="Campo normal" />
    <input aria-label="Código manual" data-physical-scanner-input />
    <PhysicalScannerStatus {...scanner} />
  </>;
}

describe("ScannerBurstDetector", () => {
  it("accepts a fast keyboard-wedge burst finished by Enter", () => {
    const detector = new ScannerBurstDetector({ minLength: 4, maxInterKeyDelayMs: 50 });
    expect(detector.push("8", 10)).toBeNull();
    expect(detector.push("4", 22)).toBeNull();
    expect(detector.push("1", 34)).toBeNull();
    expect(detector.push("2", 46)).toBeNull();
    expect(detector.push("Enter", 58)).toBe("8412");
  });

  it("rejects ordinary slow typing and short bursts", () => {
    const detector = new ScannerBurstDetector({ minLength: 4, maxInterKeyDelayMs: 40 });
    detector.push("A", 10);
    detector.push("B", 100);
    detector.push("C", 190);
    detector.push("D", 280);
    expect(detector.push("Enter", 290)).toBeNull();
    detector.push("1", 400);
    detector.push("2", 410);
    expect(detector.push("Enter", 420)).toBeNull();
  });

  it("detects only duplicates inside the configured window", () => {
    expect(isAccidentalDuplicate("ABC", { value: "ABC", at: 1000 }, 1800, 1200)).toBe(true);
    expect(isAccidentalDuplicate("ABC", { value: "ABC", at: 1000 }, 2300, 1200)).toBe(false);
    expect(isAccidentalDuplicate("XYZ", { value: "ABC", at: 1000 }, 1100, 1200)).toBe(false);
  });
});

describe("usePhysicalScanner", () => {
  afterEach(() => cleanup());

  it("captures without focus, reports success and ignores an accidental duplicate", async () => {
    const onScan = vi.fn(() => true);
    render(<Harness onScan={onScan} />);

    scan(window, "8412345678901", 100);
    await waitFor(() => expect(onScan).toHaveBeenCalledWith("8412345678901"));
    expect(screen.getByText("Código leído: 8412345678901")).toBeTruthy();

    scan(window, "8412345678901", 500);
    await screen.findByText("Lectura duplicada ignorada");
    expect(onScan).toHaveBeenCalledTimes(1);
  });

  it("does not intercept normal editable fields", () => {
    const onScan = vi.fn(() => true);
    render(<Harness onScan={onScan} />);
    const normalInput = screen.getByRole("textbox", { name: "Campo normal" });
    const enter = scan(normalInput, "NORMAL", 100);
    expect(enter.defaultPrevented).toBe(false);
    expect(onScan).not.toHaveBeenCalled();
  });

  it("recognises scanner bursts in the marked manual field without blocking its characters", async () => {
    const onScan = vi.fn(() => true);
    render(<Harness onScan={onScan} />);
    const scannerInput = screen.getByRole("textbox", { name: "Código manual" });
    [..."DEV-CAFE"].forEach((key, index) => {
      const event = dispatchKey(scannerInput, key, 100 + index * 10);
      expect(event.defaultPrevented).toBe(false);
    });
    const enter = dispatchKey(scannerInput, "Enter", 190);
    expect(enter.defaultPrevented).toBe(true);
    await waitFor(() => expect(onScan).toHaveBeenCalledWith("DEV-CAFE"));
  });

  it("exposes an accessible error announcement when processing fails", async () => {
    render(<Harness onScan={() => false} />);
    scan(window, "INVALID", 100);
    expect(await screen.findByText("No se pudo procesar el código")).toBeTruthy();
  });
});
