import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode } from "../types";
import "./ZoomableProductImage.css";

type Props = { src: string; alt: string; locale: LocaleCode };

export function ZoomableProductImage({ src, alt, locale }: Props) {
  const t = createTranslator(locale);
  const thumbnailRef = useRef<HTMLImageElement>(null);
  const [open, setOpen] = useState(false);
  useEffect(() => setOpen(false), [src]);

  return <>
    <img ref={thumbnailRef} src={src} alt={alt} className="product-image-zoom-thumbnail"
      tabIndex={0} title={t("product.image.enlarge")}
      onDoubleClick={(event) => { event.stopPropagation(); setOpen(true); }}
      onKeyDown={(event) => {
        if (event.key !== "Enter" && event.key !== " ") return;
        event.preventDefault();
        event.stopPropagation();
        setOpen(true);
      }} />
    {open && <ProductImageViewer src={src} alt={alt} locale={locale}
      onClose={() => setOpen(false)} returnFocus={() => thumbnailRef.current?.focus({ preventScroll: true })} />}
  </>;
}

function ProductImageViewer({ src, alt, locale, onClose, returnFocus }: Props & {
  onClose: () => void;
  returnFocus: () => void;
}) {
  const t = createTranslator(locale);
  const descriptionId = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const imageRef = useRef<HTMLImageElement>(null);
  const returnFocusRef = useRef(returnFocus);
  const [zoom, setZoom] = useState(1);
  const [size, setSize] = useState<{ width: number; height: number }>();

  function fitImage() {
    const image = imageRef.current;
    if (!image?.naturalWidth || !image.naturalHeight) return;
    const scale = Math.min(Math.max(1, window.innerWidth - 64) / image.naturalWidth,
      Math.max(1, window.innerHeight - 64) / image.naturalHeight);
    setSize({ width: image.naturalWidth * scale, height: image.naturalHeight * scale });
  }

  useLayoutEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (typeof dialog.showModal === "function") dialog.showModal();
    else dialog.setAttribute("open", "");
    dialog.focus({ preventScroll: true });
    return () => {
      if (typeof dialog.close === "function") dialog.close();
      else dialog.removeAttribute("open");
      returnFocusRef.current();
    };
  }, []);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    function zoomWithWheel(event: WheelEvent) {
      event.preventDefault();
      event.stopPropagation();
      const units = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? window.innerHeight : 1;
      const factor = Math.exp(-Math.max(-500, Math.min(500, event.deltaY * units)) * 0.002);
      setZoom((current) => Math.max(0.25, Math.min(5, current * factor)));
    }
    dialog.addEventListener("wheel", zoomWithWheel, { passive: false });
    window.addEventListener("resize", fitImage);
    return () => {
      dialog.removeEventListener("wheel", zoomWithWheel);
      window.removeEventListener("resize", fitImage);
    };
  }, []);

  return createPortal(<dialog ref={dialogRef} className="product-image-viewer" role="dialog"
    aria-modal="true" aria-label={t("product.image.preview")} aria-describedby={descriptionId} tabIndex={-1}
    onCancel={(event) => { event.preventDefault(); event.stopPropagation(); onClose(); }}
    onKeyDown={(event) => {
      event.stopPropagation();
      if (event.key === "Escape") { event.preventDefault(); onClose(); }
      if (event.key === "Tab") event.preventDefault();
    }}
    onKeyUp={(event) => event.stopPropagation()}
    onPointerDown={(event) => event.stopPropagation()}
    onMouseDown={(event) => event.stopPropagation()}
    onDoubleClick={(event) => event.stopPropagation()}
    onClick={(event) => {
      event.stopPropagation();
      if (event.target !== imageRef.current) onClose();
    }}>
    <p id={descriptionId} className="product-image-viewer-instructions">{t("product.image.previewHelp")}</p>
    <img ref={imageRef} src={src} alt={alt} draggable={false} onLoad={fitImage}
      style={{ ...size, transform: `scale(${zoom})` }} />
  </dialog>, document.body);
}
