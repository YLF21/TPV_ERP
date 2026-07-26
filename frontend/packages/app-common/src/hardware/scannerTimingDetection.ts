export type ScannerTimingConfig = {
  minimumLength: number;
  maximumInterKeyMs: number;
  maximumDurationMs: number;
};

export type ScannerTimingCapture = {
  value: string;
  startedAt: number;
  lastKeyAt: number;
  originalInput: string;
};

export type ScannerTimingDecision = {
  next: ScannerTimingCapture;
  detected: boolean;
  restoreInput?: string;
  completedCode?: string;
};

export const defaultScannerTimingConfig: ScannerTimingConfig = {
  minimumLength: 6,
  maximumInterKeyMs: 50,
  maximumDurationMs: 500,
};

export const idleScannerTimingCapture: ScannerTimingCapture = {
  value: "",
  startedAt: 0,
  lastKeyAt: 0,
  originalInput: "",
};

export function normalizeScannerTimingConfig(
  config: Partial<ScannerTimingConfig>,
): ScannerTimingConfig {
  return {
    minimumLength: boundedInteger(config.minimumLength, 2, 64, 6),
    maximumInterKeyMs: boundedInteger(config.maximumInterKeyMs, 10, 500, 50),
    maximumDurationMs: boundedInteger(config.maximumDurationMs, 50, 5_000, 500),
  };
}

export function scannerTimingKeyDecision(
  capture: ScannerTimingCapture,
  key: string,
  config: ScannerTimingConfig,
  now: number,
  currentInput: string,
): ScannerTimingDecision {
  const normalized = normalizeScannerTimingConfig(config);

  if (key.length === 1) {
    const continuesSequence = capture.value.length > 0
      && now - capture.lastKeyAt <= normalized.maximumInterKeyMs;
    const next = continuesSequence
      ? {
          ...capture,
          value: `${capture.value}${key}`.slice(0, 256),
          lastKeyAt: now,
        }
      : {
          value: key,
          startedAt: now,
          lastKeyAt: now,
          originalInput: currentInput,
        };
    return { next, detected: false };
  }

  if (key !== "Enter") {
    if (["Shift", "Control", "Alt", "Meta", "CapsLock"].includes(key)) {
      return { next: capture, detected: false };
    }
    return { next: idleScannerTimingCapture, detected: false };
  }

  const duration = now - capture.startedAt;
  const detected = capture.value.length >= normalized.minimumLength
    && duration <= normalized.maximumDurationMs
    && now - capture.lastKeyAt <= normalized.maximumInterKeyMs;
  return {
    next: idleScannerTimingCapture,
    detected,
    ...(detected
      ? {
          restoreInput: capture.originalInput,
          completedCode: capture.value,
        }
      : {}),
  };
}

function boundedInteger(
  value: number | undefined,
  minimum: number,
  maximum: number,
  fallback: number,
) {
  if (!Number.isFinite(value)) return fallback;
  return Math.min(maximum, Math.max(minimum, Math.round(value!)));
}
