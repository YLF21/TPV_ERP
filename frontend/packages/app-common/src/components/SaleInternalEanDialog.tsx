import { useEffect, useMemo, useState } from "react";
import type { LocaleCode } from "../types";
import {
  assignInternalEan,
  reserveInternalEan,
  reserveManualEan,
  validateInternalEan,
  type InternalEanFormat,
  type InternalEanProduct,
  type InternalEanReservation,
  type InternalEanValidation,
} from "../sale/internalEan";
import {
  saleOperationAuthorizationComplete,
  saleOperationCredentials,
  type SaleOperationAuthorization,
} from "../sale/operationSecurity";
import { SaleOperationAuthorizationFields } from "./SaleOperationAuthorizationFields";

type Props = {
  open: boolean;
  locale: LocaleCode;
  token?: string;
  products: InternalEanProduct[];
  initialProductId?: string;
  authorization: SaleOperationAuthorization;
  onClose: () => void;
  onAssigned: (product: InternalEanProduct) => void;
  onCreateProduct: (reservation: InternalEanReservation) => void;
};

const copy = {
  es: {
    title: "Generador y comprobador EAN",
    generate: "Generar",
    check: "Comprobar",
    format: "Formato",
    generateAction: "Generar código",
    code: "Código EAN",
    checkAction: "Comprobar código",
    valid: "Código válido",
    invalid: "Código no válido",
    invalidLength: "Debe tener 8 o 13 cifras.",
    invalidDigit: "El dígito de control no coincide.",
    numeric: "Solo se admiten cifras.",
    reserved: "Código reservado durante 15 minutos",
    assignTitle: "Asignar código",
    search: "Buscar producto existente",
    noProducts: "No hay productos coincidentes",
    currentBarcode2: "Código de barras 2 actual",
    confirmReplace: "Confirmo que deseo reemplazar el código de barras 2 actual",
    assign: "Asignar como código de barras 2",
    newProduct: "Crear producto nuevo",
    prepare: "Preparar asignación",
    close: "Cerrar",
    error: "No se pudo completar la operación.",
  },
  en: {
    title: "EAN generator and checker", generate: "Generate", check: "Check", format: "Format",
    generateAction: "Generate code", code: "EAN code", checkAction: "Check code", valid: "Valid code",
    invalid: "Invalid code", invalidLength: "It must contain 8 or 13 digits.", invalidDigit: "The check digit does not match.",
    numeric: "Only digits are allowed.", reserved: "Code reserved for 15 minutes", assignTitle: "Assign code",
    search: "Search existing product", noProducts: "No matching products", currentBarcode2: "Current barcode 2",
    confirmReplace: "I confirm replacing the current barcode 2", assign: "Assign as barcode 2",
    newProduct: "Create new product", prepare: "Prepare assignment", close: "Close", error: "The operation could not be completed.",
  },
  zh: {
    title: "EAN 生成与校验", generate: "生成", check: "校验", format: "格式", generateAction: "生成代码",
    code: "EAN 代码", checkAction: "校验代码", valid: "代码有效", invalid: "代码无效",
    invalidLength: "必须为 8 位或 13 位数字。", invalidDigit: "校验位不匹配。", numeric: "仅允许数字。",
    reserved: "代码已保留 15 分钟", assignTitle: "分配代码", search: "搜索现有商品", noProducts: "没有匹配的商品",
    currentBarcode2: "当前条码 2", confirmReplace: "我确认替换当前条码 2", assign: "分配为条码 2",
    newProduct: "创建新商品", prepare: "准备分配", close: "关闭", error: "无法完成操作。",
  },
} as const;

type EanCopy = { [Key in keyof typeof copy.es]: string };

function validationMessage(
  validation: InternalEanValidation | null,
  t: EanCopy,
) {
  if (!validation) return "";
  if (validation.valid) return t.valid;
  if (validation.reason === "INVALID_LENGTH") return t.invalidLength;
  if (validation.reason === "INVALID_CHECK_DIGIT") return t.invalidDigit;
  if (validation.reason === "NON_NUMERIC") return t.numeric;
  return t.invalid;
}

export function SaleInternalEanDialog({
  open,
  locale,
  token,
  products,
  initialProductId = "",
  authorization,
  onClose,
  onAssigned,
  onCreateProduct,
}: Props) {
  const t = copy[locale];
  const [tab, setTab] = useState<"GENERATE" | "CHECK">("GENERATE");
  const [format, setFormat] = useState<InternalEanFormat>("EAN_13");
  const [code, setCode] = useState("");
  const [validation, setValidation] = useState<InternalEanValidation | null>(null);
  const [reservation, setReservation] = useState<InternalEanReservation | null>(null);
  const [query, setQuery] = useState("");
  const [productId, setProductId] = useState(initialProductId);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [replaceConfirmed, setReplaceConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!open) return;
    setTab("GENERATE");
    setFormat("EAN_13");
    setCode("");
    setValidation(null);
    setReservation(null);
    setQuery("");
    setProductId(initialProductId);
    setUsername("");
    setPassword("");
    setReplaceConfirmed(false);
    setError("");
  }, [initialProductId, open]);

  const filtered = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase();
    return products.filter((product) => !normalized || [
      product.code, product.barcode, product.barcode2, product.name,
    ].some((value) => value?.toLocaleLowerCase().includes(normalized))).slice(0, 50);
  }, [products, query]);
  const selectedProduct = products.find((product) => product.id === productId);
  const replacementRequired = Boolean(
    selectedProduct?.barcode2 && selectedProduct.barcode2 !== reservation?.code,
  );
  const credentialsComplete = saleOperationAuthorizationComplete(
    authorization, username, password,
  );

  if (!open) return null;

  async function generate() {
    if (!credentialsComplete || busy) return;
    setBusy(true);
    setError("");
    try {
      const next = await reserveInternalEan(
        format,
        saleOperationCredentials(authorization, username, password),
        token,
      );
      setReservation(next);
      setCode(next.code);
      setValidation({ code: next.code, format: next.format, valid: true });
      setPassword("");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : t.error);
    } finally {
      setBusy(false);
    }
  }

  async function check() {
    if (!code.trim() || busy) return;
    setBusy(true);
    setError("");
    setReservation(null);
    try {
      setValidation(await validateInternalEan(code, token));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : t.error);
    } finally {
      setBusy(false);
    }
  }

  async function prepareManual() {
    if (!validation?.valid || !credentialsComplete || busy) return;
    setBusy(true);
    setError("");
    try {
      const next = await reserveManualEan(
        validation.code,
        saleOperationCredentials(authorization, username, password),
        token,
      );
      setReservation(next);
      setPassword("");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : t.error);
    } finally {
      setBusy(false);
    }
  }

  async function assign() {
    if (!reservation || !selectedProduct || (replacementRequired && !replaceConfirmed) || busy) return;
    setBusy(true);
    setError("");
    try {
      const product = await assignInternalEan(
        reservation.reservationId,
        selectedProduct.id,
        replaceConfirmed,
        token,
      );
      onAssigned(product);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : t.error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="sale-utility-backdrop" role="presentation">
      <section className="sale-utility-dialog sale-internal-ean-dialog" role="dialog" aria-modal="true" aria-labelledby="internal-ean-title">
        <header><h2 id="internal-ean-title">{t.title}</h2></header>
        <div className="sale-internal-ean-tabs" role="tablist">
          <button type="button" className={tab === "GENERATE" ? "active" : ""} onClick={() => { setTab("GENERATE"); setReservation(null); setValidation(null); setCode(""); }}>{t.generate}</button>
          <button type="button" className={tab === "CHECK" ? "active" : ""} onClick={() => { setTab("CHECK"); setReservation(null); setValidation(null); setCode(""); }}>{t.check}</button>
        </div>
        <div className="sale-internal-ean-body">
          {tab === "GENERATE" ? (
            <label><span>{t.format}</span><select value={format} disabled={busy || Boolean(reservation)} onChange={(event) => setFormat(event.currentTarget.value as InternalEanFormat)}><option value="EAN_13">EAN-13</option><option value="EAN_8">EAN-8</option></select></label>
          ) : (
            <label><span>{t.code}</span><input autoFocus inputMode="numeric" maxLength={13} value={code} disabled={busy || Boolean(reservation)} onChange={(event) => { setCode(event.currentTarget.value); setValidation(null); }} onKeyDown={(event) => { if (event.key === "Enter") void check(); }} /></label>
          )}
          {!reservation && (
            <SaleOperationAuthorizationFields locale={locale} authorization={authorization} username={username} password={password} disabled={busy} onUsernameChange={setUsername} onPasswordChange={setPassword} />
          )}
          {tab === "GENERATE" && !reservation && <button type="button" disabled={busy || !credentialsComplete} onClick={() => void generate()}>{t.generateAction}</button>}
          {tab === "CHECK" && !reservation && <div className="sale-internal-ean-check-actions"><button type="button" disabled={busy || !code.trim()} onClick={() => void check()}>{t.checkAction}</button>{validation?.valid && <button type="button" disabled={busy || !credentialsComplete} onClick={() => void prepareManual()}>{t.prepare}</button>}</div>}
          {validation && <p className={validation.valid ? "sale-internal-ean-valid" : "sale-internal-ean-invalid"}>{validationMessage(validation, t)}</p>}
          {reservation && (
            <div className="sale-internal-ean-reservation">
              <strong>{reservation.code}</strong><small>{t.reserved}</small>
              <h3>{t.assignTitle}</h3>
              <label><span>{t.search}</span><input value={query} onChange={(event) => setQuery(event.currentTarget.value)} /></label>
              <select size={Math.min(8, Math.max(2, filtered.length))} value={productId} onChange={(event) => { setProductId(event.currentTarget.value); setReplaceConfirmed(false); }}>
                {filtered.length === 0 && <option value="">{t.noProducts}</option>}
                {filtered.map((product) => <option key={product.id} value={product.id}>{product.code ? `${product.code} · ` : ""}{product.name ?? product.barcode ?? product.id}</option>)}
              </select>
              {selectedProduct?.barcode2 && <p>{t.currentBarcode2}: <strong>{selectedProduct.barcode2}</strong></p>}
              {replacementRequired && <label className="sale-internal-ean-confirm"><input type="checkbox" checked={replaceConfirmed} onChange={(event) => setReplaceConfirmed(event.currentTarget.checked)} /> <span>{t.confirmReplace}</span></label>}
              <div className="sale-internal-ean-assignment-actions"><button type="button" disabled={busy || !selectedProduct || (replacementRequired && !replaceConfirmed)} onClick={() => void assign()}>{t.assign}</button><button type="button" disabled={busy} onClick={() => onCreateProduct(reservation)}>{t.newProduct}</button></div>
            </div>
          )}
          {error && <p className="sale-dialog-error" role="alert">{error}</p>}
        </div>
        <footer><button type="button" disabled={busy} onClick={onClose}>{t.close}</button></footer>
      </section>
    </div>
  );
}
