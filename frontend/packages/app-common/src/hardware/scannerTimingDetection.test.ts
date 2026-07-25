import { describe, expect, it } from "vitest";
import {
  defaultScannerTimingConfig,
  idleScannerTimingCapture,
  normalizeScannerTimingConfig,
  scannerTimingKeyDecision,
} from "./scannerTimingDetection";

describe("scanner timing detection", () => {
  it("detects six rapid characters followed by Enter", () => {
    let state = idleScannerTimingCapture;
    for (const [index, key] of Array.from("841234").entries()) {
      state = scannerTimingKeyDecision(
        state,
        key,
        defaultScannerTimingConfig,
        100 + index * 20,
        index === 0 ? "12,00" : key,
      ).next;
    }

    expect(scannerTimingKeyDecision(
      state,
      "Enter",
      defaultScannerTimingConfig,
      220,
      "841234",
    )).toEqual({
      next: idleScannerTimingCapture,
      detected: true,
      restoreInput: "12,00",
      completedCode: "841234",
    });
  });

  it("does not classify ordinary short or slow typing as a scan", () => {
    let short = idleScannerTimingCapture;
    for (const [index, key] of Array.from("12345").entries()) {
      short = scannerTimingKeyDecision(
        short,
        key,
        defaultScannerTimingConfig,
        100 + index * 20,
        "",
      ).next;
    }
    expect(scannerTimingKeyDecision(
      short,
      "Enter",
      defaultScannerTimingConfig,
      210,
      "12345",
    ).detected).toBe(false);

    let slow = idleScannerTimingCapture;
    for (const [index, key] of Array.from("841234").entries()) {
      slow = scannerTimingKeyDecision(
        slow,
        key,
        defaultScannerTimingConfig,
        100 + index * 80,
        "",
      ).next;
    }
    expect(scannerTimingKeyDecision(
      slow,
      "Enter",
      defaultScannerTimingConfig,
      510,
      "841234",
    ).detected).toBe(false);
  });

  it("requires the Enter suffix within the configured key interval", () => {
    let state = idleScannerTimingCapture;
    for (const [index, key] of Array.from("841234").entries()) {
      state = scannerTimingKeyDecision(
        state,
        key,
        defaultScannerTimingConfig,
        100 + index * 20,
        "",
      ).next;
    }

    expect(scannerTimingKeyDecision(
      state,
      "Enter",
      defaultScannerTimingConfig,
      251,
      "841234",
    ).detected).toBe(false);
  });

  it("normalizes unsafe terminal configuration values", () => {
    expect(normalizeScannerTimingConfig({
      minimumLength: 0,
      maximumInterKeyMs: 900,
      maximumDurationMs: Number.NaN,
    })).toEqual({
      minimumLength: 2,
      maximumInterKeyMs: 500,
      maximumDurationMs: 500,
    });
  });
});
