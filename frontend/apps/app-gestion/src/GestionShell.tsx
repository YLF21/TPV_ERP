import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode
} from "react";
import type { UserSession } from "@tpverp/app-common";
import { CaretDown, CaretRight } from "@phosphor-icons/react";
import type { Icon } from "@phosphor-icons/react";
import type { GestionGroupLock } from "./gestionNavigation";

type Translator = (key: string) => string;

export type GestionNavigationItem = {
  key: string;
  label: string;
  icon?: Icon;
  lock?: GestionGroupLock;
  onOpen?: () => void;
  children?: GestionNavigationItem[];
};

type GestionShellProps = {
  session: UserSession;
  t: Translator;
  activeKey: string;
  navigation: GestionNavigationItem[];
  requestOpenDestination?: (item: GestionNavigationItem) => boolean | Promise<boolean>;
  children: ReactNode;
};

function usesCenteredDestinationLayout(item: GestionNavigationItem) {
  return item.lock !== "FISCAL";
}

const DEFAULT_NAVIGATION_WIDTH = 238;
const MIN_NAVIGATION_WIDTH = 210;
const MAX_NAVIGATION_WIDTH = 420;
const NAVIGATION_WIDTH_STEP = 16;

function clampNavigationWidth(width: number) {
  return Math.min(MAX_NAVIGATION_WIDTH, Math.max(MIN_NAVIGATION_WIDTH, Math.round(width)));
}

function navigationWidthStorageKey(username: string) {
  return `tpv-erp:gestion-navigation-width:${username}`;
}

function readNavigationWidth(username: string) {
  try {
    const storedWidth = Number.parseInt(window.localStorage.getItem(navigationWidthStorageKey(username)) ?? "", 10);
    return Number.isFinite(storedWidth) ? clampNavigationWidth(storedWidth) : DEFAULT_NAVIGATION_WIDTH;
  } catch {
    return DEFAULT_NAVIGATION_WIDTH;
  }
}

export function GestionShell({
  session,
  t,
  activeKey,
  navigation,
  requestOpenDestination,
  children
}: GestionShellProps) {
  const activeParent = navigation.find((item) => item.children?.some((child) => child.key === activeKey))?.key;
  const [expandedKey, setExpandedKey] = useState<string | null>(activeParent ?? null);
  const [navigationQuery, setNavigationQuery] = useState("");
  const [navigationWidth, setNavigationWidth] = useState(() => readNavigationWidth(session.username));
  const [resizingNavigation, setResizingNavigation] = useState(false);
  const searchRef = useRef<HTMLInputElement | null>(null);
  const navigationWidthRef = useRef(navigationWidth);
  const navigationResizeRef = useRef<{ pointerId: number; startX: number; startWidth: number } | null>(null);
  const destinations = useMemo(() => navigation.flatMap((item) => [
    ...(item.onOpen ? [{ ...item, pathLabel: item.label }] : []),
    ...(item.children ?? []).filter((child) => child.onOpen).map((child) => ({
      ...child,
      pathLabel: `${item.label} / ${child.label}`
    }))
  ]), [navigation]);
  const normalizedQuery = navigationQuery.trim().toLocaleLowerCase();
  const matchingDestinations = normalizedQuery
    ? destinations.filter((item) => item.pathLabel.toLocaleLowerCase().includes(normalizedQuery))
    : [];

  function openDestination(item: GestionNavigationItem) {
    const result = requestOpenDestination?.(item) ?? true;
    if (result instanceof Promise) {
      void result.then((allowed) => { if (allowed) item.onOpen?.(); });
    } else if (result) {
      item.onOpen?.();
    }
  }

  function updateNavigationWidth(width: number) {
    const clampedWidth = clampNavigationWidth(width);
    navigationWidthRef.current = clampedWidth;
    setNavigationWidth(clampedWidth);
    return clampedWidth;
  }

  function persistNavigationWidth(width = navigationWidthRef.current) {
    try {
      window.localStorage.setItem(navigationWidthStorageKey(session.username), String(width));
    } catch {
      // The resize remains available for the current session if storage is unavailable.
    }
  }

  function startNavigationResize(event: ReactPointerEvent<HTMLDivElement>) {
    if (event.button !== 0) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    navigationResizeRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startWidth: navigationWidthRef.current
    };
    setResizingNavigation(true);
  }

  function moveNavigationResize(event: ReactPointerEvent<HTMLDivElement>) {
    const resize = navigationResizeRef.current;
    if (!resize || resize.pointerId !== event.pointerId) return;
    updateNavigationWidth(resize.startWidth + event.clientX - resize.startX);
  }

  function finishNavigationResize(event: ReactPointerEvent<HTMLDivElement>) {
    const resize = navigationResizeRef.current;
    if (!resize || resize.pointerId !== event.pointerId) return;
    navigationResizeRef.current = null;
    setResizingNavigation(false);
    persistNavigationWidth();
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  }

  function resizeNavigationWithKeyboard(event: ReactKeyboardEvent<HTMLDivElement>) {
    let nextWidth: number | null = null;
    const step = event.shiftKey ? NAVIGATION_WIDTH_STEP * 2 : NAVIGATION_WIDTH_STEP;
    if (event.key === "ArrowLeft") nextWidth = navigationWidthRef.current - step;
    if (event.key === "ArrowRight") nextWidth = navigationWidthRef.current + step;
    if (event.key === "Home") nextWidth = MIN_NAVIGATION_WIDTH;
    if (event.key === "End") nextWidth = MAX_NAVIGATION_WIDTH;
    if (nextWidth === null) return;
    event.preventDefault();
    persistNavigationWidth(updateNavigationWidth(nextWidth));
  }

  useEffect(() => {
    setExpandedKey(activeParent ?? null);
  }, [activeParent]);

  useEffect(() => {
    function focusNavigationSearch(event: KeyboardEvent) {
      if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase() === "k") {
        event.preventDefault();
        searchRef.current?.focus();
      }
    }
    window.addEventListener("keydown", focusNavigationSearch);
    return () => window.removeEventListener("keydown", focusNavigationSearch);
  }, []);

  return (
    <main
      className={`gestion-screen${resizingNavigation ? " is-resizing-navigation" : ""}`}
      style={{ "--gestion-navigation-width": `${navigationWidth}px` } as CSSProperties}
    >
      <aside className="gestion-nav">
        <div className="gestion-nav-brand">
          <h1>{t("gestion.title")}</h1>
          <p>{t("gestion.subtitle")}</p>
        </div>
        <div className="gestion-nav-search">
          <label htmlFor="gestion-navigation-search">{t("gestion.navigationSearch")}</label>
          <input
            ref={searchRef}
            id="gestion-navigation-search"
            type="search"
            value={navigationQuery}
            placeholder={t("gestion.navigationSearchPlaceholder")}
            onChange={(event) => setNavigationQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape") {
                setNavigationQuery("");
                event.currentTarget.blur();
              }
            }}
          />
          <kbd>Ctrl K</kbd>
        </div>
        <nav aria-label={t("gestion.navigation") }>
          {normalizedQuery ? (
            <div className="gestion-nav-results" aria-live="polite">
              {matchingDestinations.map((item) => {
                const centeredDestination = usesCenteredDestinationLayout(item);
                return (
                  <button
                    type="button"
                    key={item.key}
                    className={`${centeredDestination ? "gestion-nav-destination" : "gestion-nav-standard"}${item.key === activeKey ? " selected" : ""}`}
                    onClick={() => {
                      setNavigationQuery("");
                      openDestination(item);
                    }}
                  >
                    {item.icon && <item.icon size={centeredDestination ? 20 : 16} weight={item.key === activeKey ? "fill" : "regular"} aria-hidden="true" />}
                    <span className={centeredDestination ? "gestion-nav-destination-label" : undefined}>{item.pathLabel}</span>
                  </button>
                );
              })}
              {matchingDestinations.length === 0 && <p>{t("gestion.navigationSearchEmpty")}</p>}
            </div>
          ) : navigation.map((item) => {
            const hasChildren = Boolean(item.children?.length);
            const groupOpen = expandedKey === item.key;
            const groupActive = item.children?.some((child) => child.key === activeKey) ?? false;
            const centeredDestination = !hasChildren && usesCenteredDestinationLayout(item);
            return (
              <div className={`gestion-nav-item ${hasChildren ? "group" : "direct"}`} key={item.key}>
                <button
                  type="button"
                  className={`${hasChildren ? "" : centeredDestination ? "gestion-nav-destination" : "gestion-nav-standard"}${item.key === activeKey || groupActive ? " selected" : ""}`.trim() || undefined}
                  aria-current={item.key === activeKey ? "page" : undefined}
                  aria-expanded={hasChildren ? groupOpen : undefined}
                  onClick={() => {
                    if (hasChildren) {
                      const toggle = () => setExpandedKey((current) => current === item.key ? null : item.key);
                      if (!groupOpen && item.lock && requestOpenDestination) {
                        const result = requestOpenDestination(item);
                        if (result instanceof Promise) void result.then((allowed) => { if (allowed) toggle(); });
                        else if (result) toggle();
                      } else {
                        toggle();
                      }
                      return;
                    }
                    setExpandedKey(null);
                    openDestination(item);
                  }}
                >
                  <span>
                    {item.icon && <item.icon size={hasChildren || !centeredDestination ? 16 : 20} weight={item.key === activeKey || groupActive ? "fill" : "regular"} aria-hidden="true" />}
                    {item.label}
                  </span>
                  {hasChildren && (groupOpen
                    ? <CaretDown size={14} weight="bold" aria-hidden="true" />
                    : <CaretRight size={14} weight="bold" aria-hidden="true" />)}
                </button>
                {hasChildren && groupOpen && (
                  <div className="gestion-nav-children">
                    {item.children?.map((child) => {
                      const centeredChild = usesCenteredDestinationLayout(child);
                      return (
                        <button
                          type="button"
                          key={child.key}
                          className={`${centeredChild ? "gestion-nav-destination" : "gestion-nav-standard"}${child.key === activeKey ? " selected" : ""}`}
                          aria-current={child.key === activeKey ? "page" : undefined}
                          onClick={() => openDestination(child)}
                        >
                          {child.icon && <child.icon size={centeredChild ? 20 : 16} weight={child.key === activeKey ? "fill" : "regular"} aria-hidden="true" />}
                          <span className={centeredChild ? "gestion-nav-destination-label" : undefined}>{child.label}</span>
                        </button>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </nav>
      </aside>
      <div
        className="gestion-nav-resizer"
        role="separator"
        aria-label={t("gestion.navigationResize")}
        aria-orientation="vertical"
        aria-valuemin={MIN_NAVIGATION_WIDTH}
        aria-valuemax={MAX_NAVIGATION_WIDTH}
        aria-valuenow={navigationWidth}
        tabIndex={0}
        onPointerDown={startNavigationResize}
        onPointerMove={moveNavigationResize}
        onPointerUp={finishNavigationResize}
        onPointerCancel={finishNavigationResize}
        onKeyDown={resizeNavigationWithKeyboard}
      />
      {children}
    </main>
  );
}
