import { ArrowLeft, ArrowRight, Plus, X } from "@phosphor-icons/react";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useI18n } from "../../i18n";
import { ApiError } from "../../lib/api";
import { submitClientAccessRequest, type ClientRequestSubmission } from "../../lib/client-access-api";
import { PortalLoginLayout } from "./PortalLoginLayout";
import { accessRequestCopy } from "./tenant-access-request-copy";
import "./tenant-access-request.css";

const contactFields = [
  { name: "fullName", maxLength: 160, autoComplete: "name", wide: true },
  { name: "taxId", maxLength: 40 },
  { name: "phone", maxLength: 40, type: "tel", autoComplete: "tel" },
  { name: "email", maxLength: 254, type: "email", autoComplete: "email", wide: true },
  { name: "address", maxLength: 240, autoComplete: "street-address", wide: true },
  { name: "postalCode", maxLength: 20, autoComplete: "postal-code" },
  { name: "city", maxLength: 100, autoComplete: "address-level2" },
  { name: "province", maxLength: 100, autoComplete: "address-level1" },
  { name: "country", maxLength: 100, autoComplete: "country-name" }
] as const;

const maxStores = 20;
const headingId = "tenant-access-request-title";
const errorId = "tenant-access-request-error";
type ErrorKey = "required" | "invalidUsername" | "shortPassword" | "longPassword" | "mismatch" | "invalidEmail" | "invalid" | "tooManyRequests" | "requestFailed";
type RequestError = { key: ErrorKey; field?: string };

function newSubmissionId() {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("");
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join("-");
}

export function TenantAccessRequestScreen() {
  const { language } = useI18n();
  const copy = accessRequestCopy[language];
  const [stores, setStores] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [received, setReceived] = useState(false);
  const [error, setError] = useState<RequestError | null>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const formElement = useRef<HTMLFormElement>(null);
  const addStoreButton = useRef<HTMLButtonElement>(null);
  const storeSequence = useRef(0);
  const storeFocus = useRef<number | "add" | null>(null);
  const inFlight = useRef(false);
  const lastSubmission = useRef<{ id: string; fields: string } | null>(null);

  useEffect(() => { heading.current?.focus(); }, [received]);
  useEffect(() => {
    if (storeFocus.current === "add") addStoreButton.current?.focus();
    else if (storeFocus.current !== null) {
      formElement.current?.querySelector<HTMLInputElement>(`[data-store="${storeFocus.current}"]`)?.focus();
    }
    storeFocus.current = null;
  }, [stores]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (inFlight.current) return;
    const form = event.currentTarget;
    const data = new FormData(form);
    const value = (field: string) => String(data.get(field) ?? "");
    const requiredFields = ["username", "password", "confirmation", ...contactFields.map(field => field.name)];
    const fail = (key: ErrorKey, field: string) => {
      setError({ key, field });
      const input = form.elements.namedItem(field);
      if (input instanceof HTMLInputElement) input.focus();
    };
    const missing = requiredFields.find(field => !value(field).trim());
    if (missing) return fail("required", missing);

    const username = value("username").trim();
    if (!/^[A-Za-z0-9._@-]{3,80}$/.test(username)) return fail("invalidUsername", "username");
    const password = value("password");
    if (Array.from(password).length < 12) return fail("shortPassword", "password");
    if (new TextEncoder().encode(password).length > 72) return fail("longPassword", "password");
    if (password !== value("confirmation")) return fail("mismatch", "confirmation");
    const email = form.elements.namedItem("email");
    if (email instanceof HTMLInputElement && email.validity.typeMismatch) return fail("invalidEmail", "email");

    const details = {
      locale: language,
      username,
      fullName: value("fullName").trim(),
      taxId: value("taxId").trim(),
      email: value("email").trim(),
      phone: value("phone").trim(),
      address: value("address").trim(),
      postalCode: value("postalCode").trim(),
      city: value("city").trim(),
      province: value("province").trim(),
      country: value("country").trim(),
      requestedStores: data.getAll("requestedStores").map(store => String(store).trim()).filter(Boolean),
      notes: value("notes").trim() || undefined
    };
    const payload: ClientRequestSubmission = { ...details, password };

    inFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      const fields = JSON.stringify(details);
      const id = lastSubmission.current?.fields === fields ? lastSubmission.current.id : newSubmissionId();
      lastSubmission.current = { id, fields };
      await submitClientAccessRequest(payload, id);
      form.reset();
      lastSubmission.current = null;
      setStores([]);
      setReceived(true);
    } catch (failure) {
      const status = failure instanceof ApiError ? failure.status : undefined;
      setError({ key: status === 400 ? "invalid" : status === 429 ? "tooManyRequests" : "requestFailed" });
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  function addStore() {
    const id = ++storeSequence.current;
    storeFocus.current = id;
    setStores([...stores, id]);
  }

  function removeStore(id: number, index: number) {
    storeFocus.current = stores[index + 1] ?? stores[index - 1] ?? "add";
    setStores(stores.filter(store => store !== id));
  }

  function validation(name: string, hint?: string) {
    const invalid = error?.field === name;
    return {
      "aria-invalid": invalid || undefined,
      "aria-describedby": [hint, invalid ? errorId : undefined].filter(Boolean).join(" ") || undefined
    };
  }

  return <PortalLoginLayout portal={copy.portal} kicker="" title={copy.introTitle} accent={copy.introAccent}
    description={copy.intro} features={[]} note="" headingId={headingId} cardClassName="tenant-access-request">
    <h2 id={headingId} ref={heading} tabIndex={-1}>{received ? copy.received : copy.title}</h2>
    {received ? <p className="tenant-login-help" role="status">{copy.pending}</p> : <>
      <p id="tenant-access-request-help" className="tenant-login-help">{copy.help}</p>
      <form ref={formElement} noValidate onSubmit={submit} onChange={event => {
        if (event.target instanceof HTMLInputElement && event.target.name === "password") lastSubmission.current = null;
        setError(null);
      }} aria-describedby="tenant-access-request-help" aria-busy={busy}>
        <fieldset disabled={busy}>
          <legend>{copy.accessDetails}</legend>
          <div className="tenant-access-fields">
            <label className="tenant-access-wide">{copy.username}
              <input name="username" autoComplete="username" autoCapitalize="none" spellCheck={false} maxLength={80} required
                {...validation("username", "tenant-access-username-help")} />
              <span id="tenant-access-username-help" className="tenant-access-hint">{copy.usernameHelp}</span>
            </label>
            <label>{copy.password}
              <input name="password" type="password" autoComplete="new-password" required
                {...validation("password", "tenant-access-password-help")} />
            </label>
            <label>{copy.confirmation}
              <input name="confirmation" type="password" autoComplete="new-password" required
                {...validation("confirmation")} />
            </label>
            <p id="tenant-access-password-help" className="tenant-access-wide tenant-access-hint">{copy.passwordHelp}</p>
          </div>
        </fieldset>
        <fieldset disabled={busy}>
          <legend>{copy.contactDetails}</legend>
          <div className="tenant-access-fields">
            {contactFields.map(({ name, maxLength, ...options }) =>
              <label key={name} className={"wide" in options ? "tenant-access-wide" : undefined}>{copy[name]}
                <input name={name} maxLength={maxLength} type={"type" in options ? options.type : "text"}
                  autoComplete={"autoComplete" in options ? options.autoComplete : undefined} required {...validation(name)} />
              </label>)}
          </div>
        </fieldset>
        <fieldset disabled={busy}>
          <legend>{copy.requestedStores}</legend>
          <p id="tenant-access-stores-help" className="tenant-access-hint">{copy.storesHelp}</p>
          <div className="tenant-access-store-list">
            {stores.map((id, index) => <div key={id} className="tenant-access-store">
              <label>{copy.store} {index + 1}
                <input name="requestedStores" maxLength={200} data-store={id} aria-describedby="tenant-access-stores-help" />
              </label>
              <button className="tenant-login-secondary" type="button" aria-label={`${copy.removeStore} ${index + 1}`}
                onClick={() => removeStore(id, index)}><X size={16} aria-hidden="true" /></button>
            </div>)}
          </div>
          <button ref={addStoreButton} className="tenant-login-secondary tenant-access-add-store" type="button" disabled={stores.length >= maxStores}
            onClick={addStore} aria-describedby="tenant-access-stores-help"><Plus size={16} aria-hidden="true" />{copy.addStore}</button>
        </fieldset>
        <label>{copy.notes}<textarea name="notes" rows={3} maxLength={1000} disabled={busy} /></label>
        {error && <p id={errorId} className="notice error" role="alert">{copy[error.key]}</p>}
        <button className="tenant-login-submit" type="submit" disabled={busy}>
          {busy ? copy.sending : copy.submit}<ArrowRight size={18} aria-hidden="true" />
        </button>
      </form>
    </>}
    <a className="tenant-login-client-link tenant-access-back" href="#/"><ArrowLeft size={16} aria-hidden="true" />{copy.back}</a>
  </PortalLoginLayout>;
}
