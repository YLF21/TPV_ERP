import { useId, useLayoutEffect, useRef, useState, type FormEvent } from "react";
import { LinkSimple, ListBullets, ListNumbers, TextB, TextItalic } from "@phosphor-icons/react";
import { useI18n } from "../../i18n";
import { COMMUNICATION_HTML_LIMIT, type CommunicationContent } from "../../lib/communications-api";
import { useCommunicationCopy } from "./communication-copy";

const emailTags = new Set([
  "p", "div", "br", "strong", "b", "em", "i", "u", "s", "h1", "h2", "h3", "ul", "ol", "li", "blockquote",
  "pre", "code", "table", "thead", "tbody", "tr", "th", "td", "hr", "span", "a",
]);
const htmlNamespace = "http://www.w3.org/1999/xhtml";

function safeLink(value: string) {
  try {
    const candidate = value.trim();
    if (/[\x00-\x20\x7f]/.test(candidate)) return null;
    const url = new URL(candidate);
    if (url.protocol === "mailto:" && url.pathname) return url.href;
    if (/^https?:\/\//i.test(candidate) && url.hostname && !url.username && !url.password) return url.href;
  } catch {
    return null;
  }
  return null;
}

// Parse in an inert template and create new nodes with a fixed set of elements and attributes.
// The source editor keeps any HTML the visual editor cannot represent.
function emailFragment(html: string, purpose: "editor" | "preview") {
  const template = document.createElement("template");
  template.innerHTML = html;
  const output = document.createDocumentFragment();
  const work: { input: ParentNode; target: ParentNode }[] = [{ input: template.content, target: output }];
  for (const { input, target } of work) {
    for (const child of input.childNodes) {
      if (child.nodeType === Node.TEXT_NODE) {
        target.append(document.createTextNode(child.textContent ?? ""));
        continue;
      }
      if (!(child instanceof Element) || child.namespaceURI !== htmlNamespace || !emailTags.has(child.localName)) {
        if (purpose === "editor") return null;
        continue;
      }
      const tag = child.localName;
      const element = document.createElement(tag);
      if (purpose === "editor") {
        for (const attr of child.attributes) {
          const href = tag === "a" && attr.name === "href" && safeLink(attr.value);
          if (!href) return null;
          element.setAttribute("href", href);
        }
      }
      target.append(element);
      work.push({ input: child, target: element });
    }
  }
  return output;
}

export function EmailEditor({ html, disabled, onChange }: {
  html: string;
  disabled: boolean;
  onChange: (html: string) => void;
}) {
  const copy = useCommunicationCopy();
  const { t } = useI18n();
  const [mode, setMode] = useState<"visual" | "source">(() => emailFragment(html, "editor") ? "visual" : "source");
  const [sourceWarning, setSourceWarning] = useState(false);
  const [linkOpen, setLinkOpen] = useState(false);
  const [linkValue, setLinkValue] = useState("");
  const [linkError, setLinkError] = useState(false);
  const editor = useRef<HTMLDivElement>(null);
  const linkButton = useRef<HTMLButtonElement>(null);
  const selection = useRef<Range | null>(null);
  const sourceInput = useRef<HTMLTextAreaElement>(null);
  const moveFocus = useRef(false);
  const id = useId();

  useLayoutEffect(() => {
    if (mode === "visual" && editor.current) {
      const content = emailFragment(html, "editor");
      editor.current.replaceChildren(...(content ? [content] : []));
    }
    if (moveFocus.current) {
      (mode === "visual" ? editor.current : sourceInput.current)?.focus();
      moveFocus.current = false;
    }
    selection.current = null;
  }, [mode]);

  function setEditorMode(next: typeof mode) {
    if (next === mode) return;
    if (next === "visual" && !emailFragment(html, "editor")) {
      setSourceWarning(true);
      return;
    }
    setSourceWarning(false);
    setLinkOpen(false);
    moveFocus.current = true;
    setMode(next);
  }

  function storeSelection() {
    const current = window.getSelection();
    if (!current?.rangeCount) return;
    const range = current.getRangeAt(0);
    if (editor.current?.contains(range.commonAncestorContainer)) selection.current = range.cloneRange();
  }

  function restoreSelection() {
    const target = editor.current;
    const current = window.getSelection();
    if (!target || !current) return null;
    target.focus();
    const range = selection.current ?? document.createRange();
    if (!selection.current) {
      range.selectNodeContents(target);
      range.collapse(false);
    }
    current.removeAllRanges();
    current.addRange(range);
    return range;
  }

  function recordEdit() {
    if (editor.current) onChange(editor.current.innerHTML);
    storeSelection();
  }

  function format(command: string) {
    if (disabled || !restoreSelection()) return;
    document.execCommand(command);
    recordEdit();
  }

  function insertLink(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const url = safeLink(linkValue);
    if (!url) {
      setLinkError(true);
      return;
    }
    const range = restoreSelection();
    if (!range || disabled) return;
    if (range.collapsed) {
      const link = document.createElement("a");
      link.href = url;
      link.textContent = url;
      document.execCommand("insertHTML", false, link.outerHTML);
    } else document.execCommand("createLink", false, url);
    recordEdit();
    setLinkOpen(false);
  }

  return <div className="communication-editor">
    <div className="communication-editor-header">
      <label className="communication-editor-label" id={id} htmlFor={mode === "source" ? `${id}-source` : undefined}>{copy("message")}</label>
      <div className="segmented communication-editor-mode" role="group" aria-label={copy("message")}>
        <button type="button" className={mode === "visual" ? "active" : ""} aria-pressed={mode === "visual"}
          onClick={() => setEditorMode("visual")} disabled={disabled}>{copy("visual")}</button>
        <button type="button" className={mode === "source" ? "active" : ""} aria-pressed={mode === "source"}
          onClick={() => setEditorMode("source")} disabled={disabled}>{copy("source")}</button>
      </div>
    </div>
    {sourceWarning && <p className="module-help" role="status">{copy("sourceOnly")}</p>}
    {mode === "source" ? <textarea ref={sourceInput} id={`${id}-source`} className="control-input communication-html-source"
      spellCheck={false} rows={9} value={html} disabled={disabled} onChange={event => {
        onChange(event.target.value);
        setSourceWarning(false);
      }} /> : <>
      <div className="communication-formatting" role="group" aria-label={copy("visual")}>
        <button type="button" className="small-button" disabled={disabled} onPointerDown={event => event.preventDefault()}
          title={copy("bold")} aria-label={copy("bold")} onClick={() => format("bold")}><TextB size={18} aria-hidden /></button>
        <button type="button" className="small-button" disabled={disabled} onPointerDown={event => event.preventDefault()}
          title={copy("italic")} aria-label={copy("italic")} onClick={() => format("italic")}><TextItalic size={18} aria-hidden /></button>
        <button type="button" className="small-button" disabled={disabled} onPointerDown={event => event.preventDefault()}
          title={copy("bulletList")} aria-label={copy("bulletList")} onClick={() => format("insertUnorderedList")}><ListBullets size={18} aria-hidden /></button>
        <button type="button" className="small-button" disabled={disabled} onPointerDown={event => event.preventDefault()}
          title={copy("numberList")} aria-label={copy("numberList")} onClick={() => format("insertOrderedList")}><ListNumbers size={18} aria-hidden /></button>
        <button type="button" className="small-button" ref={linkButton} disabled={disabled} aria-expanded={linkOpen}
          title={copy("link")} aria-label={copy("link")}
          onClick={() => {
            storeSelection();
            setLinkValue("");
            setLinkError(false);
            setLinkOpen(open => !open);
          }}><LinkSimple size={18} aria-hidden /></button>
      </div>
      {linkOpen && <form className="communication-link-form" onSubmit={insertLink}>
        <label>{copy("linkUrl")}<input className="control-input" type="text" inputMode="url" autoFocus
          value={linkValue} required disabled={disabled} onChange={event => {
            setLinkValue(event.target.value);
            setLinkError(false);
          }} /></label>
        <button className="secondary-button" disabled={disabled}>{copy("insert")}</button>
        <button className="secondary-button" type="button" onClick={() => {
          setLinkOpen(false);
          linkButton.current?.focus();
        }}>{t("close")}</button>
        {linkError && <p role="alert">{copy("linkInvalid")}</p>}
      </form>}
      <div ref={editor} className="communication-editor-body" role="textbox" aria-multiline="true" aria-labelledby={id}
        aria-disabled={disabled} contentEditable={!disabled} suppressContentEditableWarning tabIndex={disabled ? -1 : 0}
        onInput={recordEdit} onBlur={storeSelection} onKeyUp={storeSelection} onPointerUp={storeSelection}
        onClick={event => { if ((event.target as HTMLElement).closest("a")) event.preventDefault(); }}
        onDrop={event => event.preventDefault()}
        onPaste={event => {
          event.preventDefault();
          if (disabled) return;
          document.execCommand("insertText", false, event.clipboardData.getData("text/plain"));
          recordEdit();
        }} />
    </>}
    {html.length > COMMUNICATION_HTML_LIMIT && <p role="alert">{copy("lengthExceeded")}</p>}
  </div>;
}

export function EmailPreview({ content }: { content: CommunicationContent }) {
  const copy = useCommunicationCopy();
  const container = document.createElement("div");
  container.append(emailFragment(content.html, "preview")!);
  const source = `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"><style>body{margin:12px;font:14px/1.5 system-ui,sans-serif;color:#17283e;overflow-wrap:anywhere}a{color:#1f5a94;text-decoration:underline}table{border-collapse:collapse}th,td{padding:5px;border:1px solid #cddbea}pre{white-space:pre-wrap}</style></head><body>${container.innerHTML}</body></html>`;
  return <div className="communication-preview">
    {content.html
      ? <iframe title={copy("preview")} sandbox="" referrerPolicy="no-referrer" srcDoc={source} />
      : <p>{copy("previewEmpty")}</p>}
    {content.text && <details><summary>{copy("plainText")}</summary><pre>{content.text}</pre></details>}
  </div>;
}
