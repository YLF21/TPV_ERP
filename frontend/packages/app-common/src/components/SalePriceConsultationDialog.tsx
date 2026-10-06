import { WindowCloseButton } from "./WindowCloseButton";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, apiRequest } from "../api/client";
import { apiBaseUrl } from "../api/runtime";
import { defaultScannerTimingConfig, idleScannerTimingCapture, scannerTimingKeyDecision } from "../hardware/scannerTimingDetection";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import type { SaleInterfaceMode } from "./saleInterfacePreferences";

export type SalePriceConsultation = {
  productId: string;
  code?: string | null;
  name?: string | null;
  hasImage: boolean;
  salePrice: number | string;
  activePriceType: "NORMAL" | "MEMBER_PRICE" | "OFFER_PRICE" | "OFFER_DISCOUNT";
  memberPrice?: number | string | null;
  offerPrice?: number | string | null;
  offerDiscountPercent?: number | string | null;
  offerUntil?: string | null;
};

type Props = {
  locale: LocaleCode;
  interfaceMode?: SaleInterfaceMode;
  token?: string;
  onClose: () => void;
};

function numberLocale(locale: LocaleCode) {
  if (locale === "en") return "en-GB";
  if (locale === "zh") return "zh-CN";
  return "es-ES";
}

function money(value: number | string | null | undefined, locale: LocaleCode) {
  return Number(value ?? 0).toLocaleString(numberLocale(locale), {
    style: "currency",
    currency: "EUR",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

function percentage(value: number | string | null | undefined, locale: LocaleCode) {
  return Number(value ?? 0).toLocaleString(numberLocale(locale), {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  });
}

function date(value: string, locale: LocaleCode) {
  const [year, month, day] = value.split("-").map(Number);
  return new Intl.DateTimeFormat(numberLocale(locale)).format(new Date(year, month - 1, day));
}

export function SalePriceConsultationDialog({ locale, token, onClose, interfaceMode = "KEYBOARD" }: Props) {
  const t = createTranslator(locale);
  const requestGeneration = useRef(0);
  const lookupTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const requestController = useRef<AbortController | null>(null);
  const scannerCapture = useRef(idleScannerTimingCapture);
  const inputRef = useRef<HTMLInputElement>(null);
  const [identifier, setIdentifier] = useState("");
  const [submittedIdentifier, setSubmittedIdentifier] = useState("");
  const [result, setResult] = useState<SalePriceConsultation | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [imageSource, setImageSource] = useState("");
  const [imageState, setImageState] = useState<"idle" | "loading" | "ready" | "unavailable">("idle");

  useEffect(() => () => {
    requestGeneration.current += 1;
    if (lookupTimer.current) clearTimeout(lookupTimer.current);
    requestController.current?.abort();
  }, []);

  useEffect(() => {
    if (!result) {
      setImageSource("");
      setImageState("idle");
      return;
    }
    if (!result.hasImage || !token) {
      setImageSource("");
      setImageState("unavailable");
      return;
    }

    let active = true;
    let objectUrl = "";
    setImageSource("");
    setImageState("loading");
    void fetch(`${apiBaseUrl}/products/${encodeURIComponent(result.productId)}/image?thumbnail=true`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then((response) => {
      if (!response.ok) throw new Error("product_image_unavailable");
      return response.blob();
    }).then((blob) => {
      if (!active) return;
      objectUrl = URL.createObjectURL(blob);
      setImageSource(objectUrl);
      setImageState("ready");
    }).catch(() => {
      if (!active) return;
      setImageSource("");
      setImageState("unavailable");
    });

    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [result, token]);

  function cancelLookup() {
    if (lookupTimer.current) clearTimeout(lookupTimer.current);
    lookupTimer.current = null;
    requestGeneration.current += 1;
    requestController.current?.abort();
    requestController.current = null;
  }

  function changeIdentifier(value: string) {
    cancelLookup();
    setIdentifier(value);
    setSubmittedIdentifier("");
    setResult(null);
    setError("");
    setLoading(false);
    if (value.trim()) {
      lookupTimer.current = setTimeout(() => void consult(value.trim()), 1000);
    }
  }

  async function consult(value: string, scanCompleted = false) {
    cancelLookup();
    if (!value) return;
    const generation = ++requestGeneration.current;
    const controller = new AbortController();
    requestController.current = controller;
    setSubmittedIdentifier(value);
    // Scanner Enter completes the capture immediately, ready for another scan while loading.
    if (scanCompleted) setIdentifier("");
    setLoading(true);
    setError("");
    setResult(null);
    try {
      const product = await apiRequest<SalePriceConsultation>(
        `/products/sale/price-consultation?identifier=${encodeURIComponent(value)}`,
        { token, signal: controller.signal },
      );
      if (generation !== requestGeneration.current) return;
      setResult(product);
      // Preserve unfinished manual input on 404; a matched code is ready to be replaced.
      if (!scanCompleted && document.activeElement === inputRef.current) inputRef.current?.select();
    } catch (requestError) {
      if (generation !== requestGeneration.current) return;
      setError(requestError instanceof ApiError && requestError.status === 404
        ? t("sale.priceConsultation.notFound")
        : t("sale.priceConsultation.error"));
    } finally {
      if (generation === requestGeneration.current) {
        requestController.current = null;
        setLoading(false);
      }
    }
  }

  function submitScan(event: FormEvent) {
    event.preventDefault();
    const value = identifier.trim();
    if (value) void consult(value, true);
  }

  const visibleIdentifier = identifier || submittedIdentifier;

  return (
    <div className="sale-action-overlay" role="presentation">
      <section
        className={`sale-action-dialog wide sale-price-consultation${interfaceMode === "TOUCH" ? " sale-price-consultation-touch" : ""}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby="sale-price-consultation-title"
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            event.preventDefault();
            onClose();
          }
        }}
      >
        <header>
          <h2 id="sale-price-consultation-title">{t("sale.priceConsultation.title")}</h2>
          <WindowCloseButton type="button" aria-label={t("common.close")} onClick={onClose} >×</WindowCloseButton>
        </header>

        <form
          className={`sale-price-consultation-form${result ? " has-result" : ""}`}
          onSubmit={submitScan}
          onPointerDown={() => queueMicrotask(() => inputRef.current?.focus())}
        >
          <input
            ref={inputRef}
            className="sale-price-consultation-capture"
            autoFocus
            autoComplete="off"
            spellCheck={false}
            value={identifier}
            onChange={(event) => changeIdentifier(event.target.value)}
            onBlur={() => { scannerCapture.current = idleScannerTimingCapture; }}
            onKeyDown={(event) => {
              if (event.ctrlKey || event.altKey || event.metaKey || event.nativeEvent.isComposing) {
                scannerCapture.current = idleScannerTimingCapture;
                return;
              }
              const decision = scannerTimingKeyDecision(
                scannerCapture.current, event.key, defaultScannerTimingConfig,
                event.timeStamp, event.currentTarget.value,
              );
              scannerCapture.current = decision.next;
              if (decision.completedCode) {
                event.preventDefault();
                void consult(decision.completedCode.trim(), true);
              }
            }}
            aria-label={t("sale.priceConsultation.identifier")}
          />
          <div className={result ? "sale-price-consultation-product" : undefined}>
            {!loading && !error && result && (
              <div
                className={`sale-price-consultation-image ${imageState}`}
                aria-label={imageState === "loading"
                  ? t("sale.priceConsultation.imageLoading")
                  : imageState === "ready"
                    ? result.name ?? t("sale.main.unnamedProduct")
                    : t("product.image.empty")}
                aria-busy={imageState === "loading"}
              >
                {imageSource
                  ? <img src={imageSource} alt={result.name ?? t("sale.main.unnamedProduct")} />
                  : imageState === "loading"
                    ? <span>{t("sale.priceConsultation.imageLoading")}</span>
                    : <span>{t("product.image.empty")}</span>}
              </div>
            )}
            <div className="sale-price-consultation-details">
              <div className="sale-price-consultation-display" aria-live="polite">
                <p className={visibleIdentifier ? "code" : "prompt"}>
                  {visibleIdentifier || t("sale.priceConsultation.scanPrompt")}
                </p>
                {loading && <p className="status">{t("sale.priceConsultation.loading")}</p>}
                {!loading && error && <p className="status error" role="alert">{error}</p>}
                {!loading && !error && result && (
                  <p className="name">{result.name ?? t("sale.main.unnamedProduct")}</p>
                )}
              </div>

              {!loading && !error && result && (
                <div className="sale-price-consultation-result">
                  <dl>
                    <div className="primary">
                      <dt>{t("sale.priceConsultation.salePrice")}</dt>
                      <dd>{money(result.salePrice, locale)}</dd>
                    </div>
                    {result.activePriceType === "MEMBER_PRICE" && result.memberPrice != null && (
                      <div className="special">
                        <dt>{t("sale.priceConsultation.memberPrice")}</dt>
                        <dd>{money(result.memberPrice, locale)}</dd>
                      </div>
                    )}
                    {result.activePriceType === "OFFER_PRICE" && result.offerPrice != null && (
                      <div className="special">
                        <dt>{t("sale.priceConsultation.offerPrice")}</dt>
                        <dd>{money(result.offerPrice, locale)}</dd>
                      </div>
                    )}
                    {result.activePriceType === "OFFER_DISCOUNT" && result.offerDiscountPercent != null && (
                      <div className="special">
                        <dt>{t("sale.priceConsultation.offerDiscount")}</dt>
                        <dd>{percentage(result.offerDiscountPercent, locale)}%</dd>
                      </div>
                    )}
                    {(result.activePriceType === "OFFER_PRICE"
                      || result.activePriceType === "OFFER_DISCOUNT") && result.offerUntil && (
                      <div>
                        <dt>{t("sale.priceConsultation.offerUntil")}</dt>
                        <dd>{date(result.offerUntil, locale)}</dd>
                      </div>
                    )}
                  </dl>
                </div>
              )}
            </div>
          </div>
        </form>
      </section>
    </div>
  );
}
