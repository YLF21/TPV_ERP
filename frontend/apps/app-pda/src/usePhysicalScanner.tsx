import { useCallback, useEffect, useRef, useState } from "react";
import type { LocaleCode } from "@tpverp/app-common";

export type ScannerFeedback = "ready" | "reading" | "success" | "duplicate" | "error";

export type ScannerDetectorOptions = {
  minLength?: number;
  maxInterKeyDelayMs?: number;
  suffixDelayMs?: number;
};

export class ScannerBurstDetector {
  private buffer = "";
  private lastKeyAt = 0;
  private readonly minLength: number;
  private readonly maxInterKeyDelayMs: number;
  private readonly suffixDelayMs: number;

  constructor(options: ScannerDetectorOptions = {}) {
    this.minLength = options.minLength ?? 4;
    this.maxInterKeyDelayMs = options.maxInterKeyDelayMs ?? 55;
    this.suffixDelayMs = options.suffixDelayMs ?? 120;
  }

  reset() {
    this.buffer = "";
    this.lastKeyAt = 0;
  }

  push(key: string, timestamp: number) {
    if (key === "Enter" || key === "Tab") {
      const value = this.buffer;
      const suffixIsTimely = this.lastKeyAt > 0 && timestamp - this.lastKeyAt <= this.suffixDelayMs;
      this.reset();
      return suffixIsTimely && value.length >= this.minLength ? value : null;
    }
    if (key.length !== 1 || key < " ") {
      this.reset();
      return null;
    }
    if (!this.lastKeyAt || timestamp - this.lastKeyAt <= this.maxInterKeyDelayMs) {
      this.buffer += key;
    } else {
      this.buffer = key;
    }
    this.lastKeyAt = timestamp;
    return null;
  }

  get length() {
    return this.buffer.length;
  }
}

export function isEditableScannerTarget(target: EventTarget | null) {
  if (typeof HTMLElement === "undefined" || !(target instanceof HTMLElement)) return false;
  return target.isContentEditable || Boolean(target.closest("input, textarea, select, [contenteditable='true']"));
}

export function isPhysicalScannerInputTarget(target: EventTarget | null) {
  return typeof HTMLElement !== "undefined" && target instanceof HTMLElement && Boolean(target.closest("[data-physical-scanner-input]"));
}

export function isAccidentalDuplicate(value: string, previous: { value: string; at: number } | null, now: number, windowMs: number) {
  return Boolean(previous && previous.value === value && now - previous.at < windowMs);
}

function scannerFeedbackText(locale: LocaleCode, feedback: ScannerFeedback, value?: string) {
  const messages = {
    es: { ready: "Lector preparado", reading: "Leyendo código…", success: "Código leído", duplicate: "Lectura duplicada ignorada", error: "No se pudo procesar el código" },
    en: { ready: "Scanner ready", reading: "Reading code…", success: "Code scanned", duplicate: "Duplicate scan ignored", error: "The code could not be processed" },
    zh: { ready: "扫码器已就绪", reading: "正在读取条码…", success: "条码读取成功", duplicate: "已忽略重复扫码", error: "无法处理条码" }
  }[locale];
  const text = messages[feedback];
  return value && feedback === "success" ? `${text}: ${value}` : text;
}

function scannerSignal(feedback: Exclude<ScannerFeedback, "ready" | "reading">) {
  if (typeof window === "undefined") return;
  const vibration = feedback === "success" ? 45 : feedback === "duplicate" ? [35, 35] : [90, 45, 90];
  navigator.vibrate?.(vibration);
  try {
    const AudioContextClass = (window as typeof window & { webkitAudioContext?: typeof AudioContext }).AudioContext
      ?? (window as typeof window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AudioContextClass) return;
    const context = new AudioContextClass();
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    oscillator.frequency.value = feedback === "success" ? 880 : feedback === "duplicate" ? 440 : 220;
    gain.gain.setValueAtTime(0.045, context.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + 0.09);
    oscillator.connect(gain);
    gain.connect(context.destination);
    oscillator.start();
    oscillator.stop(context.currentTime + 0.1);
    oscillator.addEventListener("ended", () => void context.close(), { once: true });
  } catch {
    // El sonido es una mejora progresiva; vibración y estado visual siguen disponibles.
  }
}

export function usePhysicalScanner({
  enabled,
  locale,
  onScan,
  duplicateWindowMs = 1200,
  minLength = 4,
  maxInterKeyDelayMs = 55
}: {
  enabled: boolean;
  locale: LocaleCode;
  onScan: (value: string) => Promise<boolean | void> | boolean | void;
  duplicateWindowMs?: number;
  minLength?: number;
  maxInterKeyDelayMs?: number;
}) {
  const callbackRef = useRef(onScan);
  const detectorRef = useRef(new ScannerBurstDetector({ minLength, maxInterKeyDelayMs }));
  const previousRef = useRef<{ value: string; at: number } | null>(null);
  const processingRef = useRef(false);
  const feedbackTimerRef = useRef<number | null>(null);
  const [feedback, setFeedback] = useState<ScannerFeedback>("ready");
  const [announcement, setAnnouncement] = useState(scannerFeedbackText(locale, "ready"));

  useEffect(() => { callbackRef.current = onScan; }, [onScan]);
  useEffect(() => {
    detectorRef.current = new ScannerBurstDetector({ minLength, maxInterKeyDelayMs });
  }, [maxInterKeyDelayMs, minLength]);

  const showFeedback = useCallback((next: ScannerFeedback, value?: string) => {
    if (feedbackTimerRef.current != null) window.clearTimeout(feedbackTimerRef.current);
    setFeedback(next);
    setAnnouncement(scannerFeedbackText(locale, next, value));
    if (next !== "ready" && next !== "reading") {
      scannerSignal(next);
      feedbackTimerRef.current = window.setTimeout(() => {
        setFeedback("ready");
        setAnnouncement(scannerFeedbackText(locale, "ready"));
      }, 1800);
    }
  }, [locale]);

  useEffect(() => {
    if (!enabled) {
      detectorRef.current.reset();
      return;
    }
    const keydown = (event: KeyboardEvent) => {
      const scannerInput = isPhysicalScannerInputTarget(event.target);
      if (event.defaultPrevented || event.ctrlKey || event.altKey || event.metaKey || event.isComposing || (isEditableScannerTarget(event.target) && !scannerInput)) {
        detectorRef.current.reset();
        return;
      }
      const detector = detectorRef.current;
      const value = detector.push(event.key, event.timeStamp || performance.now());
      if (detector.length >= 2) {
        if (!scannerInput) event.preventDefault();
        setFeedback("reading");
        setAnnouncement(scannerFeedbackText(locale, "reading"));
      }
      if (!value) return;
      event.preventDefault();
      const now = Date.now();
      if (isAccidentalDuplicate(value, previousRef.current, now, duplicateWindowMs)) {
        showFeedback("duplicate");
        return;
      }
      if (processingRef.current) {
        showFeedback("duplicate");
        return;
      }
      processingRef.current = true;
      Promise.resolve(callbackRef.current(value))
        .then((accepted) => {
          if (accepted === false) {
            showFeedback("error");
          } else {
            previousRef.current = { value, at: now };
            showFeedback("success", value);
          }
        })
        .catch(() => showFeedback("error"))
        .finally(() => { processingRef.current = false; });
    };
    window.addEventListener("keydown", keydown, true);
    return () => window.removeEventListener("keydown", keydown, true);
  }, [duplicateWindowMs, enabled, locale, showFeedback]);

  useEffect(() => () => {
    if (feedbackTimerRef.current != null) window.clearTimeout(feedbackTimerRef.current);
  }, []);

  return { feedback, announcement };
}

export function PhysicalScannerStatus({ feedback, announcement }: { feedback: ScannerFeedback; announcement: string }) {
  return <div className={`pda-physical-scanner ${feedback}`} aria-live="polite" aria-atomic="true">
    <span className="pda-physical-scanner-dot" aria-hidden="true" />
    <span>{announcement}</span>
  </div>;
}
