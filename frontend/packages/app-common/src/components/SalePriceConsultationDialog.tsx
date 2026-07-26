import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, apiRequest } from "../api/client";
import { apiBaseUrl } from "../api/runtime";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";

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

export function SalePriceConsultationDialog({ locale, token, onClose }: Props) {
  const t = createTranslator(locale);
  const requestGeneration = useRef(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const [identifier, setIdentifier] = useState("");
  const [submittedIdentifier, setSubmittedIdentifier] = useState("");
  const [result, setResult] = useState<SalePriceConsultation | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [imageSource, setImageSource] = useState("");
  const [imageState, setImageState] = useState<"idle" | "loading" | "ready" | "unavailable">("idle");

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

  async function consult(event: FormEvent) {
    event.preventDefault();
    const value = identifier.trim();
    if (!value || loading) return;
    const generation = ++requestGeneration.current;
    setSubmittedIdentifier(value);
    setIdentifier("");
    setLoading(true);
    setError("");
    setResult(null);
    try {
      const product = await apiRequest<SalePriceConsultation>(
        `/products/sale/price-consultation?identifier=${encodeURIComponent(value)}`,
        { token },
      );
      if (generation !== requestGeneration.current) return;
      setResult(product);
    } catch (requestError) {
      if (generation !== requestGeneration.current) return;
      setError(requestError instanceof ApiError && requestError.status === 404
        ? t("sale.priceConsultation.notFound")
        : t("sale.priceConsultation.error"));
    } finally {
      if (generation === requestGeneration.current) {
        setLoading(false);
        queueMicrotask(() => inputRef.current?.focus());
      }
    }
  }

  const visibleIdentifier = identifier || submittedIdentifier;

  return (
    <div className="sale-action-overlay" role="presentation">
      <section
        className="sale-action-dialog wide sale-price-consultation"
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
          <button type="button" aria-label={t("common.close")} onClick={onClose}>×</button>
        </header>

        <form
          className={`sale-price-consultation-form${result ? " has-result" : ""}`}
          onSubmit={(event) => void consult(event)}
          onPointerDown={() => queueMicrotask(() => inputRef.current?.focus())}
        >
          <input
            ref={inputRef}
            className="sale-price-consultation-capture"
            autoFocus
            autoComplete="off"
            spellCheck={false}
            value={identifier}
            onChange={(event) => {
              if (submittedIdentifier || result || error) {
                setSubmittedIdentifier("");
                setResult(null);
                setError("");
              }
              setIdentifier(event.target.value);
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
