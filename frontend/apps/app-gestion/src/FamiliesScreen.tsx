import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
  type ReactNode,
} from "react";
import {
  ApiError,
  apiRequest,
  hasPermission,
  ProductThumbnail as AuthenticatedProductThumbnail,
  TableSortButton,
  type UserSession,
} from "@tpverp/app-common";
import {
  createFamily,
  createSubfamily,
  deleteFamily,
  deleteSubfamily,
  loadFamilies,
  loadFamilyDeleteImpact,
  loadFamilyProducts,
  loadSubfamilies,
  loadSubfamilyDeleteImpact,
  moveProducts,
  searchFamilyHierarchy,
  suggestNextFamilyCode,
  suggestNextSubfamilySuffix,
  updateFamily,
  updateSubfamily,
  type DeleteDependency,
  type DeleteImpact,
  type Family,
  type FamilyProduct,
  type FamilyProductPage,
  type FamilyProductSortBy,
  type FamilyProductSortDirection,
  type MoveProductsRequest,
  type Subfamily,
  type FamilyHierarchySearch,
} from "./familiesApi";

type Translate = (
  key: string,
  values?: Record<string, string | number>,
) => string;
type Request = typeof apiRequest;
type NodeSelection = { kind: "family" | "subfamily"; id: string } | null;
type SelectedProduct = Pick<
  FamilyProduct,
  "version" | "familyId" | "subfamilyId"
>;
type ProductSort = {
  by: FamilyProductSortBy;
  direction: FamilyProductSortDirection;
};
type Editor = {
  kind: "family" | "subfamily";
  id: string;
  name: string;
  code: string;
  familyId: string;
  familySearch: string;
  suffix: string;
};
type ImpactState = {
  kind: "family" | "subfamily";
  id: string;
  data: DeleteImpact;
} | null;

function errorText(cause: unknown, t: Translate, fallback: string) {
  if (cause instanceof ApiError) {
    const code =
      typeof cause.problem?.code === "string" ? cause.problem.code : "";
    const detail =
      typeof cause.problem?.detail === "string" ? cause.problem.detail : "";
    if (code && t(code) !== code) return t(code);
    if (["stale_version", "version_conflict", "conflict"].includes(code))
      return t("gestion.families.moveConflict");
    if (detail && t(detail) !== detail) return t(detail);
    if (detail) return detail;
  }
  return t(fallback);
}
function isProductVersionConflict(cause: unknown): cause is ApiError {
  return (
    cause instanceof ApiError &&
    cause.status === 409 &&
    cause.problem?.code === "PRODUCT_VERSION_CONFLICT" &&
    cause.problem?.action === "RELOAD_PRODUCTS"
  );
}
function translated(
  t: Translate,
  key: string,
  values: Record<string, string | number>,
) {
  return Object.entries(values).reduce(
    (result, [name, value]) => result.replaceAll(`{${name}}`, String(value)),
    t(key),
  );
}
function normalizeSearch(value: string) {
  return value
    .normalize("NFC")
    .toUpperCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f\u1ab0-\u1aff\u1dc0-\u1dff\u20d0-\u20ff\ufe20-\ufe2f]/g, "")
    .toLowerCase();
}
function isBusinessCode(value: string) {
  return /^[0-9]{3}$/.test(value) && Number(value) >= 1 && Number(value) <= 999;
}
function impactValue(value: unknown) {
  const count = typeof value === "number" ? value : Number(value);
  return Number.isFinite(count) && count > 0 ? count : 0;
}
export function normalizeImpact(value: Record<string, unknown>): DeleteImpact {
  const dependencies = Array.isArray(value.dependencies)
    ? value.dependencies
        .map((dependency): string | DeleteDependency | null => {
          if (typeof dependency === "string") return dependency;
          if (!dependency || typeof dependency !== "object") return null;
          const record = dependency as Record<string, unknown>;
          return {
            sourceType:
              typeof record.sourceType === "string"
                ? record.sourceType
                : typeof record.type === "string"
                  ? record.type
                  : undefined,
            targetType:
              typeof record.targetType === "string"
                ? record.targetType
                : undefined,
            id: typeof record.id === "string" ? record.id : undefined,
            targetId:
              typeof record.targetId === "string" ? record.targetId : undefined,
            name: typeof record.name === "string" ? record.name : undefined,
          };
        })
        .filter(
          (dependency): dependency is string | DeleteDependency =>
            dependency !== null,
        )
    : [];
  return {
    products: impactValue(
      value.products ?? value.productCount ?? value.productos,
    ),
    promotions: impactValue(
      value.promotions ?? value.promotionCount ?? value.promociones,
    ),
    rules: impactValue(
      value.rules ??
        value.ruleCount ??
        value.priceRuleCount ??
        value.priceRules ??
        value.reglas,
    ),
    blocked: Boolean(value.blocked ?? value.bloqueado),
    dependencies,
  };
}
export function formatDeleteDependency(
  dependency: string | DeleteDependency,
  t: Translate,
) {
  if (typeof dependency === "string") return dependency;
  const source = dependency.sourceType
    ? t(`gestion.families.dependencyType.${dependency.sourceType}`)
    : "";
  const target = dependency.targetType
    ? t(`gestion.families.dependencyType.${dependency.targetType}`)
    : "";
  const type =
    [source, target]
      .filter(
        (value) =>
          value && !value.startsWith("gestion.families.dependencyType."),
      )
      .join(" → ") || t("gestion.families.dependencyType.unknown");
  const name = dependency.name || dependency.id || dependency.targetId || "";
  return name ? `${type}: ${name}` : type;
}
function nodeKey(node: NodeSelection) {
  return node ? `${node.kind}:${node.id}` : "";
}
function nodeCode(family: Family, subfamily?: Subfamily) {
  return subfamily
    ? subfamily.subfamilyCode
    : family.defaultFamily
      ? "000"
      : family.familyCode;
}
function sortFamilies(rows: Family[]) {
  return [...rows].sort(
    (left, right) =>
      (left.defaultFamily
        ? -1
        : right.defaultFamily
          ? 1
          : left.familyCode.localeCompare(right.familyCode)) ||
      left.name.localeCompare(right.name, "es", { sensitivity: "base" }),
  );
}
function sortSubfamilies(rows: Subfamily[]) {
  return [...rows].sort(
    (left, right) =>
      left.subfamilyCode.localeCompare(right.subfamilyCode) ||
      left.name.localeCompare(right.name, "es", { sensitivity: "base" }),
  );
}

function FamilyModal({
  title,
  titleId,
  className,
  closeLabel,
  busy,
  onClose,
  children,
}: {
  title: string;
  titleId: string;
  className: string;
  closeLabel: string;
  busy: boolean;
  onClose: () => void;
  children: ReactNode;
}) {
  return (
    <div className="gestion-modal-backdrop" role="presentation">
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className={`gestion-security-dialog gestion-families-dialog ${className}`}
      >
        <header>
          <h2 id={titleId}>{title}</h2>
          <button
            type="button"
            aria-label={closeLabel}
            title={closeLabel}
            disabled={busy}
            onClick={onClose}
          >
            <span aria-hidden="true">×</span>
          </button>
        </header>
        {children}
      </section>
    </div>
  );
}

function isInteractiveProductRowTarget(target: EventTarget | null) {
  return (
    target instanceof Element &&
    Boolean(
      target.closest(
        "a, button, input, label, select, textarea, [role=button], [role=link]",
      ),
    )
  );
}

function ProductTable({
  page,
  loading,
  error,
  selectedProducts,
  onToggle,
  onTogglePage,
  onLoadMore,
  sort,
  onSort,
  scrollResetKey,
  t,
  token,
}: {
  page: FamilyProductPage | null;
  loading: boolean;
  error: string;
  selectedProducts: Map<string, SelectedProduct>;
  onToggle: (product: FamilyProduct) => void;
  onTogglePage: (checked: boolean) => void;
  onLoadMore: () => void;
  sort: ProductSort;
  onSort: (column: FamilyProductSortBy) => void;
  scrollResetKey: string;
  t: Translate;
  token: string;
}) {
  const headerRef = useRef<HTMLInputElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const visible = page?.items ?? [];
  const selection = selectedProducts;
  const selectedVisible = visible.filter((row) => selection.has(row.id)).length;
  useEffect(() => {
    if (headerRef.current)
      headerRef.current.indeterminate =
        selectedVisible > 0 && selectedVisible < visible.length;
  }, [selectedVisible, visible.length]);
  const loadMoreIfNearEnd = useCallback(() => {
    const scroll = scrollRef.current;
    if (!scroll || loading || !page?.hasMore || error) return;
    if (
      scroll.clientHeight > 0 &&
      scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight <= 120
    )
      onLoadMore();
  }, [error, loading, onLoadMore, page?.hasMore]);
  useEffect(() => {
    const frame = window.requestAnimationFrame(loadMoreIfNearEnd);
    return () => window.cancelAnimationFrame(frame);
  }, [loadMoreIfNearEnd, visible.length]);
  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = 0;
  }, [scrollResetKey]);
  const ariaSort = (column: FamilyProductSortBy) =>
    sort.by !== column
      ? ("none" as const)
      : sort.direction === "asc"
        ? ("ascending" as const)
        : ("descending" as const);
  const sortDirection = (column: FamilyProductSortBy) =>
    sort.by === column ? sort.direction : null;
  const sortLabel = (label: string) => `${t("party.sortBy")} ${label}`;
  const codeLabel = t("gestion.families.productCode");
  const nameLabel = t("party.name");
  const salePriceLabel = t("gestion.families.salePrice");
  return (
    <section
      className={`gestion-families-products ${error ? "has-error" : ""}`}
      aria-labelledby="families-products-title"
      aria-busy={loading}
    >
      <header className="gestion-families-products-header">
        <div>
          <h2 id="families-products-title">
            {t("gestion.families.productsTitle")}
          </h2>
          <p>
            {page
              ? translated(
                  t,
                  page.total === undefined
                    ? "gestion.families.productsLoaded"
                    : "gestion.families.productsCount",
                  { count: page.total ?? page.items.length },
                )
              : t("gestion.families.selectNode")}
          </p>
        </div>
      </header>
      {error && (
        <p className="gestion-error" role="alert">
          {error}
        </p>
      )}
      {loading && !page ? (
        <div className="gestion-families-product-state">
          {t("gestion.families.productsLoading")}
        </div>
      ) : !page ? (
        error ? null : (
          <div className="gestion-families-product-state">
            {t("gestion.families.selectNode")}
          </div>
        )
      ) : (
        <>
          <div
            ref={scrollRef}
            className="gestion-families-products-table-wrap"
            onScroll={() => {
              const scroll = scrollRef.current;
              if (
                scroll &&
                !loading &&
                page.hasMore &&
                scroll.clientHeight > 0 &&
                scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight <=
                  120
              )
                onLoadMore();
            }}
          >
            <table className="report-table gestion-families-products-table">
              <colgroup>
                <col style={{ width: 40 }} />
                <col style={{ width: 82 }} />
                <col style={{ width: 130 }} />
                <col />
                <col style={{ width: 150 }} />
              </colgroup>
              <thead>
                <tr>
                  <th scope="col">
                    <input
                      ref={headerRef}
                      type="checkbox"
                      aria-label={t("gestion.families.selectPage")}
                      checked={
                        visible.length > 0 && selectedVisible === visible.length
                      }
                      onChange={(event) => onTogglePage(event.target.checked)}
                    />
                  </th>
                  <th scope="col">{t("gestion.families.thumbnail")}</th>
                  <th scope="col" aria-sort={ariaSort("code")}>
                    <TableSortButton
                      label={sortLabel(codeLabel)}
                      direction={sortDirection("code")}
                      onSort={() => onSort("code")}
                    >
                      {codeLabel}
                    </TableSortButton>
                  </th>
                  <th scope="col" aria-sort={ariaSort("name")}>
                    <TableSortButton
                      label={sortLabel(nameLabel)}
                      direction={sortDirection("name")}
                      onSort={() => onSort("name")}
                    >
                      {nameLabel}
                    </TableSortButton>
                  </th>
                  <th scope="col" className="numeric" aria-sort={ariaSort("salePrice")}>
                    <TableSortButton
                      label={sortLabel(salePriceLabel)}
                      direction={sortDirection("salePrice")}
                      onSort={() => onSort("salePrice")}
                    >
                      {salePriceLabel}
                    </TableSortButton>
                  </th>
                </tr>
              </thead>
              <tbody>
                {visible.map((product) => (
                  <tr
                    key={product.id}
                    className={[
                      selection.has(product.id) ? "selected" : "",
                      !product.active ? "is-inactive" : "",
                    ]
                      .filter(Boolean)
                      .join(" ")}
                    aria-selected={selection.has(product.id)}
                    tabIndex={0}
                    onClick={(event) => {
                      if (isInteractiveProductRowTarget(event.target)) return;
                      event.currentTarget.focus();
                      onToggle(product);
                    }}
                    onKeyDown={(event) => {
                      if (isInteractiveProductRowTarget(event.target)) return;
                      if (event.key !== "Enter" && event.key !== " ") return;
                      event.preventDefault();
                      onToggle(product);
                    }}
                  >
                    <td>
                      <input
                        type="checkbox"
                        aria-label={translated(
                          t,
                          "gestion.families.selectProduct",
                          {
                            name: product.name,
                          },
                        )}
                        checked={selection.has(product.id)}
                        onChange={() => onToggle(product)}
                      />
                    </td>
                    <td>
                      <AuthenticatedProductThumbnail
                        productId={product.id}
                        imageId={product.imageId}
                        name={product.name}
                        token={token}
                        className="gestion-family-product-thumb"
                      />
                    </td>
                    <td>
                      <code>{product.code || product.barcode || "—"}</code>
                    </td>
                    <td
                      className="gestion-family-product-name-cell"
                      title={product.name}
                    >
                      <span className="gestion-family-product-name">
                        {product.name}
                      </span>
                      {!product.active && (
                        <em className="gestion-family-inactive">
                          {t("gestion.families.inactive")}
                        </em>
                      )}
                    </td>
                    <td className="numeric">
                      {product.salePrice === null
                        ? "—"
                        : new Intl.NumberFormat(undefined, {
                            style: "currency",
                            currency: "EUR",
                          }).format(product.salePrice)}
                    </td>
                  </tr>
                ))}
                {visible.length === 0 && (
                  <tr>
                    <td colSpan={5} className="gestion-families-product-state">
                      {t("gestion.families.productsEmpty")}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
            {loading && (
              <div
                className="gestion-families-products-load-more"
                role="status"
                aria-live="polite"
              >
                {t("gestion.families.productsLoading")}
              </div>
            )}
          </div>
        </>
      )}
    </section>
  );
}

export function FamiliesScreen({
  session,
  t,
  request = apiRequest,
}: {
  session: UserSession;
  t: Translate;
  request?: Request;
}) {
  const [families, setFamilies] = useState<Family[]>([]);
  const [subfamilies, setSubfamilies] = useState<Record<string, Subfamily[]>>(
    {},
  );
  const [loadingSubfamilies, setLoadingSubfamilies] = useState<Set<string>>(
    new Set(),
  );
  // An entry in subfamilies can be a partial result (for example after creating
  // a child from the editor). Keep the authoritative-load bit separately so a
  // later expand still fetches the complete branch.
  const [fullyLoadedFamilyIds, setFullyLoadedFamilyIds] = useState<Set<string>>(
    new Set(),
  );
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [selectedNode, setSelectedNode] = useState<NodeSelection>(null);
  const [productSort, setProductSort] = useState<ProductSort>({
    by: "name",
    direction: "asc",
  });
  const [products, setProducts] = useState<FamilyProductPage | null>(null);
  const [selectedProducts, setSelectedProducts] = useState<
    Map<string, SelectedProduct>
  >(new Map());
  const [loading, setLoading] = useState(true);
  const [productsLoading, setProductsLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [productError, setProductError] = useState("");
  const [status, setStatus] = useState("");
  const [editor, setEditor] = useState<Editor | null>(null);
  const [moveOpen, setMoveOpen] = useState(false);
  const [moveExpanded, setMoveExpanded] = useState<Set<string>>(new Set());
  const [moveSearch, setMoveSearch] = useState("");
  const [moveSearchResults, setMoveSearchResults] = useState<FamilyHierarchySearch[] | null>(null);
  const [moveSearchLoading, setMoveSearchLoading] = useState(false);
  const [moveSearchHasMore, setMoveSearchHasMore] = useState(false);
  const [moveSearchNextCursor, setMoveSearchNextCursor] = useState("");
  const [moveSearchError, setMoveSearchError] = useState("");
  const [moveSearchRetry, setMoveSearchRetry] = useState(0);
  const [moveFocusKey, setMoveFocusKey] = useState("");
  const [familyComboOpen, setFamilyComboOpen] = useState(false);
  const [familyComboActiveId, setFamilyComboActiveId] = useState("");
  const [generalConfirm, setGeneralConfirm] = useState(false);
  const [moveTarget, setMoveTarget] = useState<NodeSelection>(null);
  const [impact, setImpact] = useState<ImpactState>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const loadRef = useRef(0);
  const productLoadRef = useRef(0);
  const productAppendRequestRef = useRef<number | null>(null);
  const requestedProductCursorsRef = useRef<Set<string>>(new Set());
  const previousProductSortRef = useRef(productSort);
  const productSortRef = useRef(productSort);
  productSortRef.current = productSort;
  const suffixRef = useRef(0);
  const moveSearchRequestRef = useRef(0);
  const canManage =
    hasPermission(session, "ADMIN") ||
    hasPermission(session, "GESTION_PRODUCTO") ||
    hasPermission(session, "PRODUCTS_WRITE");
  const message = useCallback(
    (cause: unknown, fallback: string) => errorText(cause, t, fallback),
    [t],
  );
  const familyById = useMemo(
    () => new Map(families.map((family) => [family.id, family])),
    [families],
  );
  const selectedProductIds = useMemo(
    () => new Set(selectedProducts.keys()),
    [selectedProducts],
  );
  const visibleNodes = useMemo(
    () =>
      families.flatMap((family) => [
        { kind: "family" as const, id: family.id },
        ...(expanded.has(family.id)
          ? (subfamilies[family.id] ?? []).map((subfamily) => ({
              kind: "subfamily" as const,
              id: subfamily.id,
            }))
          : []),
      ]),
    [expanded, families, subfamilies],
  );
  const parentFamilyForNode = useCallback(
    (node: NodeSelection) => {
      if (!node) return null;
      if (node.kind === "family") return familyById.get(node.id) ?? null;
      for (const family of families)
        if (
          (subfamilies[family.id] ?? []).some(
            (subfamily) => subfamily.id === node.id,
          )
        )
          return family;
      return null;
    },
    [families, familyById, subfamilies],
  );
  const loadTree = useCallback(async () => {
    const requestId = ++loadRef.current;
    setLoading(true);
    setError("");
    try {
      const nextFamilies = sortFamilies(
        await loadFamilies(session.accessToken, request),
      );
      if (requestId !== loadRef.current) return;
      setFamilies(nextFamilies);
      setSubfamilies({});
      setFullyLoadedFamilyIds(new Set());
      setLoadingSubfamilies(new Set());
      setExpanded(new Set());
      setSelectedNode(null);
    } catch (cause) {
      if (requestId === loadRef.current)
        setError(message(cause, "gestion.families.loadError"));
    } finally {
      if (requestId === loadRef.current) setLoading(false);
    }
  }, [message, request, session.accessToken]);
  const ensureSubfamilies = useCallback(
    async (family: Family) => {
      if (fullyLoadedFamilyIds.has(family.id) || loadingSubfamilies.has(family.id)) return;
      setLoadingSubfamilies((current) => new Set(current).add(family.id));
      try {
        const rows = await loadSubfamilies(
          family.id,
          session.accessToken,
          request,
          family.familyCode,
        );
        setSubfamilies((current) => {
          const merged = new Map(
            (current[family.id] ?? []).map((item) => [item.id, item]),
          );
          rows.forEach((item) => merged.set(item.id, item));
          return { ...current, [family.id]: sortSubfamilies([...merged.values()]) };
        });
        setFullyLoadedFamilyIds((current) => new Set(current).add(family.id));
      } catch (cause) {
        setError(message(cause, "gestion.families.subfamiliesLoadError"));
      } finally {
        setLoadingSubfamilies((current) => {
          const next = new Set(current);
          next.delete(family.id);
          return next;
        });
      }
    },
    [fullyLoadedFamilyIds, loadingSubfamilies, message, request, session.accessToken],
  );
  useEffect(() => {
    void loadTree();
    return () => {
      ++loadRef.current;
    };
  }, [loadTree]);
  const loadProducts = useCallback(
    async (
      node: NodeSelection,
      page: number,
      cursor = "",
      append = false,
      sortBy: FamilyProductSortBy = "name",
      sortDirection: FamilyProductSortDirection = "asc",
    ) => {
      if (
        append &&
        (!cursor ||
          productAppendRequestRef.current !== null ||
          requestedProductCursorsRef.current.has(cursor))
      )
        return;
      const requestId = ++productLoadRef.current;
      if (append) {
        productAppendRequestRef.current = requestId;
        requestedProductCursorsRef.current.add(cursor);
      } else {
        productAppendRequestRef.current = null;
        requestedProductCursorsRef.current = new Set();
      }
      if (!node) {
        setProducts(null);
        setProductsLoading(false);
        return;
      }
      if (!append) setProducts(null);
      setProductsLoading(true);
      setProductError("");
      try {
        const next = await loadFamilyProducts(
          node,
          session.accessToken,
          request,
          cursor,
          page,
          25,
          sortBy,
          sortDirection,
        );
        if (requestId === productLoadRef.current) {
          setProducts((current) => {
            if (!append || !current)
              return {
                ...next,
                hasMore: next.hasMore && Boolean(next.nextCursor),
              };
            const items = [...current.items];
            const positions = new Map(
              items.map((item, index) => [item.id, index]),
            );
            for (const item of next.items) {
              const position = positions.get(item.id);
              if (position === undefined) {
                positions.set(item.id, items.length);
                items.push(item);
              } else {
                items[position] = item;
              }
            }
            return {
              ...next,
              items,
              total: next.total ?? current.total,
              hasMore:
                next.hasMore &&
                Boolean(next.nextCursor) &&
                next.nextCursor !== cursor &&
                !requestedProductCursorsRef.current.has(next.nextCursor),
            };
          });
        }
      } catch (cause) {
        if (requestId === productLoadRef.current) {
          if (!append) setProducts(null);
          else requestedProductCursorsRef.current.delete(cursor);
          setProductError(message(cause, "gestion.families.productsError"));
        }
      } finally {
        if (requestId === productLoadRef.current) {
          if (productAppendRequestRef.current === requestId)
            productAppendRequestRef.current = null;
          setProductsLoading(false);
        }
      }
    },
    [message, request, session.accessToken],
  );
  useEffect(() => {
    setSelectedProducts(new Map());
    const sort = productSortRef.current;
    void loadProducts(
      selectedNode,
      0,
      "",
      false,
      sort.by,
      sort.direction,
    );
  }, [loadProducts, selectedNode]);
  useEffect(() => {
    const previous = previousProductSortRef.current;
    if (
      previous.by === productSort.by &&
      previous.direction === productSort.direction
    )
      return;
    previousProductSortRef.current = productSort;
    void loadProducts(
      selectedNode,
      0,
      "",
      false,
      productSort.by,
      productSort.direction,
    );
  }, [loadProducts, productSort, selectedNode]);
  const loadMoreProducts = useCallback(() => {
    if (
      !selectedNode ||
      !products?.hasMore ||
      !products.nextCursor ||
      productsLoading
    )
      return;
    void loadProducts(
      selectedNode,
      products.page + 1,
      products.nextCursor,
      true,
      productSort.by,
      productSort.direction,
    );
  }, [loadProducts, productSort, products, productsLoading, selectedNode]);
  function sortProducts(column: FamilyProductSortBy) {
    setProductSort((current) => ({
      by: column,
      direction:
        current.by === column && current.direction === "asc" ? "desc" : "asc",
    }));
  }
  useEffect(() => {
    if (!editor && !impact && !moveOpen && !generalConfirm) return undefined;
    const close = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape" && !busy) {
        setEditor(null);
        setImpact(null);
        ++moveSearchRequestRef.current;
        setMoveOpen(false);
        setGeneralConfirm(false);
      }
    };
    window.addEventListener("keydown", close);
    return () => window.removeEventListener("keydown", close);
  }, [busy, editor, generalConfirm, impact, moveOpen]);
  function selectNode(node: NodeSelection) {
    setSelectedNode(node);
    setStatus("");
    if (node?.kind === "family") {
      const family = familyById.get(node.id);
      if (family) {
        void ensureSubfamilies(family);
        setExpanded((current) => new Set(current).add(node.id));
      }
    }
  }
  async function requestSuffix(familyId: string) {
    const requestId = ++suffixRef.current;
    try {
      const suffix = await suggestNextSubfamilySuffix(
        familyId,
        session.accessToken,
        request,
      );
      setEditor((current) =>
        current &&
        current.kind === "subfamily" &&
        !current.id &&
        current.familyId === familyId &&
        current.suffix === "" &&
        requestId === suffixRef.current
          ? { ...current, suffix }
          : current,
      );
    } catch (cause) {
      if (requestId === suffixRef.current)
        setError(message(cause, "gestion.families.suffixSuggestionError"));
    }
  }
  async function openNewFamily() {
    if (!canManage || busy) return;
    setBusy(true);
    setError("");
    try {
      setEditor({
        kind: "family",
        id: "",
        name: "",
        code: await suggestNextFamilyCode(session.accessToken, request),
        familyId: "",
        familySearch: "",
        suffix: "",
      });
    } catch (cause) {
      setError(message(cause, "gestion.families.codeSuggestionError"));
    } finally {
      setBusy(false);
    }
  }
  async function openNewSubfamily() {
    if (!canManage || busy) return;
    const selectedFamily = parentFamilyForNode(selectedNode);
    const family =
      selectedFamily && !selectedFamily.defaultFamily ? selectedFamily : null;
    setError("");
    setFamilyComboOpen(!family);
    setEditor({
      kind: "subfamily",
      id: "",
      name: "",
      code: "",
      familyId: family?.id ?? "",
      familySearch: family ? `${family.familyCode} — ${family.name}` : "",
      suffix: "",
    });
    if (family && !family.defaultFamily) void requestSuffix(family.id);
  }
  function openEditFamily(family: Family) {
    setFamilyComboOpen(false);
    setEditor({
      kind: "family",
      id: family.id,
      name: family.name,
      code: family.familyCode,
      familyId: "",
      familySearch: "",
      suffix: "",
    });
    setError("");
  }
  function openEditSubfamily(subfamily: Subfamily) {
    setFamilyComboOpen(false);
    const family = familyById.get(subfamily.familyId);
    setEditor({
      kind: "subfamily",
      id: subfamily.id,
      name: subfamily.name,
      code: subfamily.subfamilySuffix,
      familyId: subfamily.familyId,
      familySearch: family ? `${family.familyCode} — ${family.name}` : "",
      suffix: subfamily.subfamilySuffix,
    });
    setError("");
  }
  async function save(event: FormEvent) {
    event.preventDefault();
    if (!editor || !editor.name.trim() || busy || !canManage) {
      setError(t("gestion.families.invalid"));
      return;
    }
    if (
      editor.kind === "family" &&
      !editor.id &&
      !isBusinessCode(editor.code)
    ) {
      setError(t("gestion.families.codeInvalid"));
      return;
    }
    if (editor.kind === "subfamily" && !editor.familyId) {
      setError(t("gestion.families.parentRequired"));
      return;
    }
    if (editor.kind === "subfamily" && !isBusinessCode(editor.suffix)) {
      setError(t("gestion.families.suffixInvalid"));
      return;
    }
    setBusy(true);
    setError("");
    try {
      if (editor.kind === "family") {
        const saved = editor.id
          ? await updateFamily(
              editor.id,
              editor.name.trim().toLocaleUpperCase(),
              session.accessToken,
              request,
            )
          : await createFamily(
              editor.name.trim().toLocaleUpperCase(),
              editor.code,
              session.accessToken,
              request,
            );
        setFamilies((current) =>
          sortFamilies(
            editor.id
              ? current.map((family) =>
                  family.id === saved.id ? { ...family, ...saved } : family,
                )
              : [...current, saved],
          ),
        );
      } else if (editor.id) {
        const saved = await updateSubfamily(
          editor.id,
          editor.name.trim().toLocaleUpperCase(),
          session.accessToken,
          request,
        );
        setSubfamilies((current) => ({
          ...current,
          [editor.familyId]: sortSubfamilies(
            (current[editor.familyId] ?? []).map((item) =>
              item.id === saved.id
                ? { ...item, ...saved, familyId: editor.familyId }
                : item,
            ),
          ),
        }));
      } else {
        const saved = await createSubfamily(
          editor.familyId,
          editor.name.trim().toLocaleUpperCase(),
          editor.suffix,
          session.accessToken,
          request,
        );
        setSubfamilies((current) => ({
          ...current,
          [editor.familyId]: sortSubfamilies([
            ...(current[editor.familyId] ?? []),
            saved,
          ]),
        }));
      }
      setEditor(null);
      setStatus(t("gestion.families.saved"));
    } catch (cause) {
      setError(message(cause, "gestion.families.saveError"));
    } finally {
      setBusy(false);
    }
  }
  async function askDelete(kind: "family" | "subfamily", id: string) {
    if (!canManage || busy) return;
    setBusy(true);
    setError("");
    try {
      const data =
        kind === "family"
          ? await loadFamilyDeleteImpact(id, session.accessToken, request)
          : await loadSubfamilyDeleteImpact(id, session.accessToken, request);
      setImpact({ kind, id, data: normalizeImpact(data) });
      setConfirmDelete(false);
    } catch (cause) {
      setError(message(cause, "gestion.families.impactError"));
    } finally {
      setBusy(false);
    }
  }
  async function confirmDeletion() {
    if (
      !impact ||
      busy ||
      impact.data.blocked ||
      impact.data.promotions > 0 ||
      impact.data.rules > 0 ||
      (impact.data.products > 0 && !confirmDelete)
    )
      return;
    setBusy(true);
    setError("");
    const removesCurrentSelection =
      selectedNode?.id === impact.id ||
      (impact.kind === "family" &&
        parentFamilyForNode(selectedNode)?.id === impact.id);
    try {
      if (impact.kind === "family") {
        await deleteFamily(
          impact.id,
          impact.data.products > 0,
          session.accessToken,
          request,
        );
        setFamilies((current) =>
          current.filter((family) => family.id !== impact.id),
        );
        setSubfamilies((current) => {
          const next = { ...current };
          delete next[impact.id];
          return next;
        });
      } else {
        await deleteSubfamily(
          impact.id,
          impact.data.products > 0,
          session.accessToken,
          request,
        );
        setSubfamilies((current) =>
          Object.fromEntries(
            Object.entries(current).map(([familyId, children]) => [
              familyId,
              children.filter((item) => item.id !== impact.id),
            ]),
          ),
        );
      }
      if (removesCurrentSelection) selectNode(null);
      setImpact(null);
      setStatus(t("gestion.families.deleted"));
    } catch (cause) {
      setError(message(cause, "gestion.families.deleteError"));
    } finally {
      setBusy(false);
    }
  }
  function toggleProduct(product: FamilyProduct) {
    setSelectedProducts((current) => {
      const next = new Map(current);
      if (next.has(product.id)) next.delete(product.id);
      else
        next.set(product.id, {
          version: product.version,
          familyId: product.familyId,
          subfamilyId: product.subfamilyId,
        });
      return next;
    });
  }
  function togglePage(checked: boolean) {
    setSelectedProducts((current) => {
      const next = new Map(current);
      (products?.items ?? []).forEach((product) =>
        checked
          ? next.set(product.id, {
              version: product.version,
              familyId: product.familyId,
              subfamilyId: product.subfamilyId,
            })
          : next.delete(product.id),
      );
      return next;
    });
  }
  function targetDetails(target: NodeSelection) {
    if (!target) return null;
    const family = parentFamilyForNode(target);
    if (!family && target.kind === "subfamily") {
      const remote = moveSearchResults?.find(
        (row) =>
          row.kind === "SUBFAMILY" &&
          (row.subfamilyId === target.id || row.id === target.id),
      );
      const remoteFamily = remote?.familyId
        ? familyById.get(remote.familyId) ?? null
        : null;
      if (remote && remoteFamily) {
        return {
          family: remoteFamily,
          subfamily: {
            id: remote.subfamilyId ?? remote.id,
            familyId: remote.familyId ?? remoteFamily.id,
            name: remote.name,
            subfamilyCode: remote.code,
            subfamilySuffix: remote.suffix,
          },
        };
      }
    }
    if (!family) return null;
    if (target.kind === "family") return { family, subfamily: null };
    const subfamily = (subfamilies[family.id] ?? []).find(
      (item) => item.id === target.id,
    );
    return subfamily ? { family, subfamily } : null;
  }
  async function openMove() {
    if (!canManage || selectedProducts.size === 0 || busy) return;
    setMoveTarget(null);
    setMoveExpanded(new Set());
    setMoveSearch("");
    setMoveSearchResults(null);
    setMoveSearchLoading(false);
    setMoveSearchHasMore(false);
    setMoveSearchNextCursor("");
    setMoveSearchError("");
    setMoveFocusKey("");
    setMoveOpen(true);
  }

  function closeMove() {
    ++moveSearchRequestRef.current;
    setMoveOpen(false);
    setMoveTarget(null);
    setMoveSearchError("");
    setMoveFocusKey("");
  }

  function changeMoveSearch(value: string) {
    ++moveSearchRequestRef.current;
    setMoveSearch(value);
    setMoveTarget(null);
    setMoveSearchResults(null);
    setMoveSearchLoading(false);
    setMoveSearchHasMore(false);
    setMoveSearchNextCursor("");
    setMoveSearchError("");
  }

  useEffect(() => {
    const requestId = ++moveSearchRequestRef.current;
    const normalizedQuery = normalizeSearch(moveSearch);
    if (!moveOpen || !normalizedQuery) {
      setMoveSearchResults(null);
      setMoveSearchLoading(false);
      setMoveSearchHasMore(false);
      setMoveSearchNextCursor("");
      setMoveSearchError("");
      return;
    }
    if (Array.from(normalizedQuery).length < 2) {
      setMoveSearchResults(null);
      setMoveSearchLoading(false);
      setMoveSearchHasMore(false);
      setMoveSearchNextCursor("");
      setMoveSearchError("");
      return;
    }
    setMoveSearchLoading(true);
    setMoveSearchError("");
    setError("");
    const timer = window.setTimeout(() => {
      void searchFamilyHierarchy(moveSearch, session.accessToken, request, 50)
        .then((page) => {
          if (requestId !== moveSearchRequestRef.current) return;
          setMoveSearchResults(page.items);
          setMoveSearchHasMore(page.hasMore);
          setMoveSearchNextCursor(page.nextCursor);
          setMoveSearchError("");
          setError("");
          setMoveSearchLoading(false);
        })
        .catch((cause) => {
          if (requestId !== moveSearchRequestRef.current) return;
          setMoveSearchResults(null);
          setMoveSearchLoading(false);
          setMoveSearchHasMore(false);
          setMoveSearchNextCursor("");
          const nextError = message(cause, "gestion.families.searchError");
          setMoveSearchError(nextError);
          setError(nextError);
        });
    }, 250);
    return () => window.clearTimeout(timer);
  }, [message, moveOpen, moveSearch, moveSearchRetry, request, session.accessToken]);

  const loadMoreMoveSearch = useCallback(() => {
    if (!moveOpen || !moveSearchHasMore || moveSearchLoading || !moveSearchNextCursor) return;
    const requestId = ++moveSearchRequestRef.current;
    setMoveSearchLoading(true);
    void searchFamilyHierarchy(moveSearch, session.accessToken, request, 50, moveSearchNextCursor)
      .then((page) => {
        if (requestId !== moveSearchRequestRef.current) return;
        setMoveSearchResults((current) => {
          const merged = new Map((current ?? []).map((row) => [`${row.kind}:${row.id}`, row]));
          page.items.forEach((row) => merged.set(`${row.kind}:${row.id}`, row));
          return Array.from(merged.values());
        });
        setMoveSearchHasMore(page.hasMore);
        setMoveSearchNextCursor(page.nextCursor);
        setMoveSearchError("");
        setError("");
        setMoveSearchLoading(false);
      })
      .catch((cause) => {
        if (requestId !== moveSearchRequestRef.current) return;
        setMoveSearchLoading(false);
        setMoveSearchHasMore(false);
        setMoveSearchNextCursor("");
        const nextError = message(cause, "gestion.families.searchError");
        setMoveSearchError(nextError);
        setError(nextError);
      });
  }, [message, moveOpen, moveSearch, moveSearchHasMore, moveSearchLoading, moveSearchNextCursor, request, session.accessToken]);
  function toggleMoveFamily(family: Family) {
    const opening = !moveExpanded.has(family.id);
    setMoveExpanded((current) => {
      const next = new Set(current);
      if (next.has(family.id)) next.delete(family.id);
      else next.add(family.id);
      return next;
    });
    if (opening) void ensureSubfamilies(family);
  }
  async function performMove(target: NodeSelection, toGeneral = false) {
    const details = toGeneral
      ? {
          family: families.find((family) => family.defaultFamily) ?? null,
          subfamily: null,
        }
      : targetDetails(target);
    if (
      !details?.family ||
      (!toGeneral && !target) ||
      (!toGeneral && details.family.defaultFamily)
    )
      return;
    setBusy(true);
    setError("");
    const body: MoveProductsRequest = {
      items: Array.from(selectedProducts.entries()).map(
        ([productId, selected]) => ({
          productId,
          expectedVersion: selected.version,
        }),
      ),
      familyId: toGeneral ? null : details.family.id,
      subfamilyId: toGeneral ? null : (details.subfamily?.id ?? null),
    };
    try {
      await moveProducts(body, session.accessToken, request);
      closeMove();
      setGeneralConfirm(false);
      setSelectedProducts(new Map());
      await loadProducts(
        selectedNode,
        0,
        "",
        false,
        productSort.by,
        productSort.direction,
      );
      setStatus(
        translated(t, "gestion.families.moveSuccess", {
          count: body.items.length,
        }),
      );
    } catch (cause) {
      if (isProductVersionConflict(cause)) {
        closeMove();
        setGeneralConfirm(false);
        setSelectedProducts(new Map());
        setError("");
        setStatus(t("gestion.families.versionConflict"));
        await loadProducts(
          selectedNode,
          0,
          "",
          false,
          productSort.by,
          productSort.direction,
        );
      } else {
        setError(message(cause, "gestion.families.moveError"));
      }
    } finally {
      setBusy(false);
    }
  }
  function treeKeyDown(
    event: KeyboardEvent<HTMLDivElement>,
    node: NodeSelection,
  ) {
    if (!node) return;
    const index = visibleNodes.findIndex(
      (candidate) => nodeKey(candidate) === nodeKey(node),
    );
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const next =
        visibleNodes[
          Math.max(
            0,
            Math.min(
              visibleNodes.length - 1,
              index + (event.key === "ArrowDown" ? 1 : -1),
            ),
          )
        ];
      if (next) {
        selectNode(next);
        document.getElementById(`family-tree-${nodeKey(next)}`)?.focus();
      }
    } else if (event.key === "ArrowRight" && node.kind === "family") {
      event.preventDefault();
      const family = familyById.get(node.id);
      if (family) void ensureSubfamilies(family);
      setExpanded((current) => new Set(current).add(node.id));
    } else if (event.key === "ArrowLeft") {
      event.preventDefault();
      if (node.kind === "family" && expanded.has(node.id))
        setExpanded((current) => {
          const next = new Set(current);
          next.delete(node.id);
          return next;
        });
      else if (node.kind === "subfamily") {
        const family = parentFamilyForNode(node);
        if (family) {
          selectNode({ kind: "family", id: family.id });
          document.getElementById(`family-tree-family:${family.id}`)?.focus();
        }
      }
    } else if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      selectNode(node);
    }
  }
  const moveTargetDetails = targetDetails(moveTarget);
  const moveQuery = normalizeSearch(moveSearch);
  const remoteMoveFamilyIds = useMemo(() => {
    if (!moveSearchResults) return new Set<string>();
    return new Set(
      moveSearchResults
        .map((row) => row.kind === "FAMILY" ? row.id : row.familyId)
        .filter((id): id is string => Boolean(id)),
    );
  }, [moveSearchResults]);
  const remoteMoveChildren = useMemo(() => {
    const grouped = new Map<string, FamilyHierarchySearch[]>();
    for (const row of moveSearchResults ?? []) {
      if (row.kind !== "SUBFAMILY" || !row.familyId) continue;
      const current = grouped.get(row.familyId) ?? [];
      current.push(row);
      grouped.set(row.familyId, current);
    }
    return grouped;
  }, [moveSearchResults]);
  const moveFamilies = families.filter((family) => {
    if (!moveQuery) return true;
    if (moveSearchResults) return remoteMoveFamilyIds.has(family.id);
    const familyMatch = normalizeSearch(
      `${family.familyCode} ${family.name}`,
    ).includes(moveQuery);
    const childMatch = (subfamilies[family.id] ?? []).some((subfamily) =>
      normalizeSearch(`${subfamily.subfamilyCode} ${subfamily.name}`).includes(
        moveQuery,
      ),
    );
    return familyMatch || childMatch;
  });
  const moveTreeItems = useMemo(() => {
    const items: NodeSelection[] = [];
    for (const family of moveFamilies) {
      items.push({ kind: "family", id: family.id });
      const children = moveSearchResults
        ? (remoteMoveChildren.get(family.id) ?? []).map((row) => ({
            kind: "subfamily" as const,
            id: row.subfamilyId || row.id,
          }))
        : (subfamilies[family.id] ?? []).map((child) => ({
            kind: "subfamily" as const,
            id: child.id,
          }));
      if (moveExpanded.has(family.id) || Boolean(moveQuery && moveSearchResults))
        items.push(...children);
    }
    return items;
  }, [moveExpanded, moveFamilies, moveQuery, moveSearchResults, remoteMoveChildren, subfamilies]);
  function isMoveDestinationDisabled(node: NodeSelection) {
    if (!node) return true;
    const details = targetDetails(node);
    if (!details || details.family.defaultFamily || selectedProducts.size === 0)
      return true;
    const destinationSubfamilyId = details.subfamily?.id ?? "";
    return Array.from(selectedProducts.values()).every(
      (product) =>
        product.familyId === details.family.id &&
        (product.subfamilyId || "") === destinationSubfamilyId,
    );
  }
  const enabledMoveTreeItems = moveTreeItems.filter(
    (item) => !isMoveDestinationDisabled(item),
  );
  useEffect(() => {
    if (!moveOpen) return;
    if (
      enabledMoveTreeItems.some((item) => nodeKey(item) === moveFocusKey)
    )
      return;
    setMoveFocusKey(
      enabledMoveTreeItems[0] ? nodeKey(enabledMoveTreeItems[0]) : "",
    );
  }, [enabledMoveTreeItems, moveFocusKey, moveOpen]);
  function focusMoveTreeItem(key: string) {
    if (!enabledMoveTreeItems.some((item) => nodeKey(item) === key)) return;
    setMoveFocusKey(key);
    window.setTimeout(() => {
      document.querySelector<HTMLElement>(`[data-move-tree-key="${key}"]`)?.focus();
    }, 0);
  }
  function moveTreeKeyDown(
    event: KeyboardEvent<HTMLElement>,
    node: NodeSelection,
  ) {
    if (!node || isMoveDestinationDisabled(node)) return;
    const index = enabledMoveTreeItems.findIndex(
      (item) => nodeKey(item) === nodeKey(node),
    );
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const next = enabledMoveTreeItems[
        Math.max(
          0,
          Math.min(
            enabledMoveTreeItems.length - 1,
            index + (event.key === "ArrowDown" ? 1 : -1),
          ),
        )
      ];
      if (next) focusMoveTreeItem(nodeKey(next));
      return;
    }
    if (event.key === "Home" || event.key === "End") {
      event.preventDefault();
      const next =
        enabledMoveTreeItems[
          event.key === "Home" ? 0 : enabledMoveTreeItems.length - 1
        ];
      if (next) focusMoveTreeItem(nodeKey(next));
      return;
    }
    if (event.key === "ArrowRight" && node.kind === "family") {
      event.preventDefault();
      const family = familyById.get(node.id);
      if (family && !moveExpanded.has(node.id)) toggleMoveFamily(family);
      return;
    }
    if (event.key === "ArrowLeft" && node.kind === "subfamily") {
      event.preventDefault();
      const family = parentFamilyForNode(node);
      const parent = family
        ? ({ kind: "family", id: family.id } as const)
        : null;
      if (parent && !isMoveDestinationDisabled(parent))
        focusMoveTreeItem(nodeKey(parent));
      return;
    }
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      setMoveTarget(node);
    }
  }
  useEffect(() => {
    if (!moveTarget || !moveQuery) return;
    const visible = moveTreeItems.some((item) => nodeKey(item) === nodeKey(moveTarget));
    if (!visible || isMoveDestinationDisabled(moveTarget)) setMoveTarget(null);
  }, [moveQuery, moveSearchResults, moveTarget, moveTreeItems]);
  return (
    <section
      className="gestion-families-screen"
      aria-labelledby="families-title"
    >
      <header className="gestion-families-heading">
        <div>
          <span className="gestion-section-kicker">
            {t("gestion.families.navigation")}
          </span>
          <h1 id="families-title">{t("gestion.families.title")}</h1>
          <p>{t("gestion.families.description")}</p>
        </div>
        <span className="gestion-families-scope">
          {t("gestion.families.storeScope")}
        </span>
      </header>
      {error && !editor && !impact && !moveOpen && !generalConfirm && (
        <p className="gestion-error" role="alert">
          {error}
        </p>
      )}
      {loading ? (
        <div className="gestion-families-state">
          {t("gestion.families.loading")}
        </div>
      ) : (
        <div className="gestion-families-workspace">
          <section
            className="gestion-families-tree-pane"
            aria-labelledby="family-tree-title"
          >
            <header>
              <div>
                <h2 id="family-tree-title">{t("gestion.families.families")}</h2>
                <span>{t("gestion.families.familyHint")}</span>
              </div>
              {canManage && (
                <div className="gestion-families-tree-actions">
                  <button
                    type="button"
                    className="primary"
                    disabled={busy}
                    onClick={() => void openNewFamily()}
                  >
                    {t("gestion.families.newFamily")}
                  </button>
                  <button
                    type="button"
                    className="primary"
                    disabled={busy}
                    onClick={() => void openNewSubfamily()}
                  >
                    {t("gestion.families.newSubfamily")}
                  </button>
                </div>
              )}
            </header>
            <div
              className="gestion-families-tree"
              role="tree"
              aria-label={t("gestion.families.families")}
            >
              {families.map((family) => (
                <div key={family.id} className="gestion-family-tree-branch">
                  <div
                    id={`family-tree-family:${family.id}`}
                    className={`gestion-family-tree-item ${nodeKey(selectedNode) === `family:${family.id}` ? "is-selected" : ""}`}
                    role="treeitem"
                    aria-level={1}
                    aria-selected={
                      nodeKey(selectedNode) === `family:${family.id}`
                    }
                    aria-expanded={expanded.has(family.id)}
                    aria-owns={`family-tree-group:${family.id}`}
                    tabIndex={
                      nodeKey(selectedNode) === `family:${family.id}` ||
                      (!selectedNode && family === families[0])
                        ? 0
                        : -1
                    }
                    onClick={() =>
                      selectNode({ kind: "family", id: family.id })
                    }
                    onKeyDown={(event) =>
                      treeKeyDown(event, { kind: "family", id: family.id })
                    }
                  >
                    <button
                      type="button"
                      className="gestion-family-tree-toggle"
                      tabIndex={-1}
                      aria-label={
                        expanded.has(family.id)
                          ? t("gestion.families.collapse")
                          : t("gestion.families.expand")
                      }
                      disabled={false}
                      onClick={(event) => {
                        event.stopPropagation();
                        void ensureSubfamilies(family);
                        setExpanded((current) => {
                          const next = new Set(current);
                          if (next.has(family.id)) next.delete(family.id);
                          else next.add(family.id);
                          return next;
                        });
                      }}
                    >
                      {family.defaultFamily
                        ? "·"
                        : expanded.has(family.id)
                          ? "▾"
                          : "▸"}
                    </button>
                    <span className="gestion-family-tree-code">
                      <code>{nodeCode(family)}</code>
                    </span>
                    <span className="gestion-family-tree-name">
                      {family.name}
                    </span>
                    {loadingSubfamilies.has(family.id) && (
                      <small>{t("gestion.families.loading")}</small>
                    )}
                    {family.defaultFamily && (
                      <em>{t("gestion.families.general")}</em>
                    )}
                    <div className="gestion-row-actions">
                      <button
                        type="button"
                        tabIndex={-1}
                        disabled={!canManage || family.defaultFamily || busy}
                        onClick={(event) => {
                          event.stopPropagation();
                          openEditFamily(family);
                        }}
                      >
                        {t("common.edit")}
                      </button>
                      <button
                        type="button"
                        tabIndex={-1}
                        disabled={!canManage || family.defaultFamily || busy}
                        onClick={(event) => {
                          event.stopPropagation();
                          void askDelete("family", family.id);
                        }}
                      >
                        {t("common.delete")}
                      </button>
                    </div>
                  </div>
                  {expanded.has(family.id) && (
                    <div
                      role="group"
                      id={`family-tree-group:${family.id}`}
                      className="gestion-family-tree-children"
                    >
                      {(subfamilies[family.id] ?? []).map((subfamily) => (
                        <div
                          id={`family-tree-subfamily:${subfamily.id}`}
                          key={subfamily.id}
                          className={`gestion-family-tree-item is-child ${nodeKey(selectedNode) === `subfamily:${subfamily.id}` ? "is-selected" : ""}`}
                          role="treeitem"
                          aria-level={2}
                          aria-selected={
                            nodeKey(selectedNode) ===
                            `subfamily:${subfamily.id}`
                          }
                          tabIndex={
                            nodeKey(selectedNode) ===
                            `subfamily:${subfamily.id}`
                              ? 0
                              : -1
                          }
                          aria-label={`${nodeCode(family, subfamily)} ${subfamily.name}`}
                          onClick={() =>
                            selectNode({ kind: "subfamily", id: subfamily.id })
                          }
                          onKeyDown={(event) =>
                            treeKeyDown(event, {
                              kind: "subfamily",
                              id: subfamily.id,
                            })
                          }
                        >
                          <span className="gestion-family-tree-code">
                            <code>{nodeCode(family, subfamily)}</code>
                          </span>
                          <span className="gestion-family-tree-name">
                            {subfamily.name}
                          </span>
                          <div className="gestion-row-actions">
                            <button
                              type="button"
                              tabIndex={-1}
                              disabled={!canManage || busy}
                              onClick={(event) => {
                                event.stopPropagation();
                                openEditSubfamily(subfamily);
                              }}
                            >
                              {t("common.edit")}
                            </button>
                            <button
                              type="button"
                              tabIndex={-1}
                              disabled={!canManage || busy}
                              onClick={(event) => {
                                event.stopPropagation();
                                void askDelete("subfamily", subfamily.id);
                              }}
                            >
                              {t("common.delete")}
                            </button>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </section>
          <div className="gestion-families-products-column">
            <ProductTable
              page={products}
              loading={productsLoading}
              error={productError}
              selectedProducts={selectedProducts}
              onToggle={toggleProduct}
              onTogglePage={togglePage}
              onLoadMore={loadMoreProducts}
              sort={productSort}
              onSort={sortProducts}
              scrollResetKey={`${nodeKey(selectedNode)}:${productSort.by}:${productSort.direction}`}
              t={t}
              token={session.accessToken ?? ""}
            />
            {selectedProductIds.size > 0 && canManage && (
              <footer className="gestion-families-selection-bar">
                <span>
                  {translated(t, "gestion.families.selectedCount", {
                    count: selectedProductIds.size,
                  })}
                </span>
                <div>
                  <button
                    type="button"
                    onClick={() => {
                      setMoveTarget(null);
                      void openMove();
                    }}
                    disabled={busy}
                  >
                    {t("gestion.families.move")}
                  </button>
                  <button
                    type="button"
                    onClick={() => setGeneralConfirm(true)}
                    disabled={busy}
                  >
                    {t("gestion.families.moveGeneral")}
                  </button>
                </div>
              </footer>
            )}
          </div>
        </div>
      )}
      {status && (
        <p className="gestion-success" role="status">
          {status}
        </p>
      )}
      {editor && (
        <FamilyModal
          title={
            editor.id
              ? t("gestion.families.edit")
              : editor.kind === "family"
                ? t("gestion.families.newFamily")
                : t("gestion.families.newSubfamily")
          }
          titleId="families-editor-title"
          className={`gestion-family-editor-dialog gestion-family-editor-${editor.kind} ${editor.id ? "is-edit" : "is-create"}`}
          closeLabel={t("common.close")}
          busy={busy}
          onClose={() => setEditor(null)}
        >
          <form onSubmit={(event) => void save(event)}>
            {error && (
              <p className="gestion-error" role="alert">
                {error}
              </p>
            )}
            {editor.kind === "family" ? (
              <>
                <label className="filter-field">
                  <span>{t("gestion.families.familyCode")}</span>
                  <input
                    inputMode="numeric"
                    maxLength={3}
                    disabled={Boolean(editor.id)}
                    value={editor.code}
                    onChange={(event) =>
                      setEditor({
                        ...editor,
                        code: event.target.value
                          .replace(/\D/g, "")
                          .slice(0, 3),
                      })
                    }
                  />
                </label>
                <label className="filter-field">
                  <span>{t("party.name")}</span>
                  <input
                    autoFocus
                    maxLength={64}
                    value={editor.name}
                    onChange={(event) =>
                      setEditor({
                        ...editor,
                        name: event.target.value.toLocaleUpperCase(),
                      })
                    }
                  />
                </label>
              </>
            ) : (
              <>
                <label className="filter-field gestion-family-code-field">
                  <span>{t("gestion.families.familyCode")}</span>
                  <div className="gestion-family-code-row">
                    <div className="gestion-family-combobox">
                      <input
                        autoFocus
                        disabled={Boolean(editor.id)}
                        role="combobox"
                        aria-expanded={!editor.id && familyComboOpen}
                        aria-controls="families-parent-options"
                        aria-activedescendant={
                          familyComboActiveId || undefined
                        }
                        value={editor.familySearch}
                        onFocus={() =>
                          !editor.id &&
                          !editor.familyId &&
                          setFamilyComboOpen(true)
                        }
                        onChange={(event) =>
                          (() => {
                            setFamilyComboActiveId("");
                            setEditor({
                              ...editor,
                              familySearch: event.target.value,
                              familyId: "",
                              suffix: "",
                            });
                          })()
                        }
                        onKeyDown={(event) => {
                          if (event.key === "Escape") {
                            setFamilyComboOpen(false);
                            return;
                          }
                          if (event.key === "ArrowDown") {
                            event.preventDefault();
                            setFamilyComboOpen(true);
                            const option =
                              document.querySelector<HTMLElement>(
                                "#families-parent-options [role=option]",
                              );
                            if (option) setFamilyComboActiveId(option.id);
                          }
                          if (event.key === "Enter" && familyComboActiveId) {
                            event.preventDefault();
                            document
                              .getElementById(familyComboActiveId)
                              ?.click();
                          }
                        }}
                      />
                      <button
                        type="button"
                        aria-label={t("gestion.families.openFamilySelector")}
                        disabled={Boolean(editor.id)}
                        onClick={() => {
                          setFamilyComboOpen((current) => !current);
                          setFamilyComboActiveId("");
                          setEditor({
                            ...editor,
                            familySearch: "",
                            familyId: "",
                            suffix: "",
                          });
                        }}
                      >
                        ▾
                      </button>
                      {!editor.id && familyComboOpen && (
                        <div
                          className="gestion-family-combobox-results"
                          role="listbox"
                          id="families-parent-options"
                        >
                          {families
                            .filter(
                              (family) =>
                                !family.defaultFamily &&
                                normalizeSearch(
                                  `${family.familyCode} ${family.name}`,
                                ).includes(normalizeSearch(editor.familySearch)),
                            )
                            .map((family) => (
                              <button
                                type="button"
                                role="option"
                                key={family.id}
                                id={`families-parent-option-${family.id}`}
                                onClick={() => {
                                  suffixRef.current += 1;
                                  setFamilyComboActiveId("");
                                  setEditor({
                                    ...editor,
                                    familyId: family.id,
                                    familySearch: `${family.familyCode} — ${family.name}`,
                                    suffix: "",
                                  });
                                  setFamilyComboOpen(false);
                                  void requestSuffix(family.id);
                                }}
                              >
                                {family.familyCode} — {family.name}
                              </button>
                            ))}
                        </div>
                      )}
                    </div>
                    <div className="gestion-code-composite">
                      <code>
                        {editor.familyId
                          ? (familyById.get(editor.familyId)?.familyCode ??
                            "")
                          : ""}
                      </code>
                      <input
                        inputMode="numeric"
                        maxLength={3}
                        disabled={Boolean(editor.id)}
                        value={editor.suffix}
                        aria-label={t("gestion.families.subfamilySuffix")}
                        onChange={(event) => {
                          suffixRef.current += 1;
                          setEditor({
                            ...editor,
                            suffix: event.target.value
                              .replace(/\D/g, "")
                              .slice(0, 3),
                          });
                        }}
                      />
                    </div>
                  </div>
                  <small>
                    {translated(t, "gestion.families.fullCode", {
                      code: editor.familyId
                        ? `${familyById.get(editor.familyId)?.familyCode ?? ""}${editor.suffix}`
                        : "",
                    })}
                  </small>
                </label>
                <label className="filter-field">
                  <span>{t("party.name")}</span>
                  <input
                    maxLength={64}
                    value={editor.name}
                    onChange={(event) =>
                      setEditor({
                        ...editor,
                        name: event.target.value.toLocaleUpperCase(),
                      })
                    }
                  />
                </label>
              </>
            )}
            <footer className="gestion-family-actions">
              <button
                type="button"
                disabled={busy}
                onClick={() => setEditor(null)}
              >
                {t("common.cancel")}
              </button>
              <button type="submit" className="primary" disabled={busy}>
                {t("common.save")}
              </button>
            </footer>
          </form>
        </FamilyModal>
      )}
      {moveOpen && (
        <FamilyModal
          title={t("gestion.families.moveTitle")}
          titleId="families-move-title"
          className="gestion-family-move-dialog"
          closeLabel={t("common.close")}
          busy={busy}
          onClose={closeMove}
        >
          <div className="gestion-family-modal-body">
            <p>
              {translated(t, "gestion.families.moveDescription", {
                count: selectedProductIds.size,
              })}
            </p>
            {error && !moveSearchError && (
              <p className="gestion-error" role="alert">
                {error}
              </p>
            )}
            <label className="gestion-family-move-search">
              <span>{t("gestion.families.search")}</span>
              <input
                type="search"
                role="searchbox"
                aria-label={t("gestion.families.search")}
                value={moveSearch}
                onChange={(event) => changeMoveSearch(event.target.value)}
                aria-controls="families-move-tree"
              />
              <small>{t("product.family.searchHint")}</small>
              {Array.from(normalizeSearch(moveSearch.trim())).length === 1 && (
                <small>{t("gestion.families.searchMinChars")}</small>
              )}
              {moveSearchLoading && (
                <small role="status">{t("gestion.families.searchLoading")}</small>
              )}
              {moveSearchError && (
                <span className="gestion-error" role="alert">
                  {moveSearchError}
                  <button
                    type="button"
                    onClick={() => setMoveSearchRetry((current) => current + 1)}
                  >
                    {t("product.family.searchRetry")}
                  </button>
                </span>
              )}
              {!moveSearchLoading && moveSearch.trim() && moveSearchResults && moveFamilies.length === 0 && (
                <small>{t("gestion.families.searchEmpty")}</small>
              )}
              {!moveSearchLoading && moveSearchHasMore && (
                <>
                  <small>{t("gestion.families.searchMore")}</small>
                  <button type="button" disabled={moveSearchLoading} onClick={loadMoreMoveSearch}>
                    {t("gestion.families.searchMoreButton")}
                  </button>
                </>
              )}
            </label>
            <div
              className="gestion-family-move-tree"
              id="families-move-tree"
              role="tree"
            >
              {moveFamilies.map((family) => (
                <div key={family.id} className="gestion-family-move-branch">
                  <div className="gestion-family-move-family-row">
                    <button
                      type="button"
                      className="gestion-family-move-toggle"
                      aria-label={
                        moveExpanded.has(family.id)
                          ? t("gestion.families.collapse")
                          : t("gestion.families.expand")
                      }
                      aria-expanded={moveExpanded.has(family.id)}
                      tabIndex={-1}
                      onClick={() => toggleMoveFamily(family)}
                    >
                      {moveExpanded.has(family.id) ? "▾" : "▸"}
                    </button>
                    <button
                      type="button"
                      className={
                        nodeKey(moveTarget) === `family:${family.id}`
                          ? "is-selected"
                          : ""
                      }
                      disabled={isMoveDestinationDisabled({
                        kind: "family",
                        id: family.id,
                      })}
                      role="treeitem"
                      aria-level={1}
                      aria-owns={`families-move-group:${family.id}`}
                      aria-label={`${nodeCode(family)} ${family.name}`}
                      aria-selected={nodeKey(moveTarget) === `family:${family.id}`}
                      data-move-tree-key={`family:${family.id}`}
                      tabIndex={
                        !isMoveDestinationDisabled({
                          kind: "family",
                          id: family.id,
                        }) && moveFocusKey === `family:${family.id}`
                          ? 0
                          : -1
                      }
                      onClick={() => {
                        const node = { kind: "family", id: family.id } as const;
                        if (!isMoveDestinationDisabled(node))
                          setMoveTarget(node);
                      }}
                      onFocus={() => setMoveFocusKey(`family:${family.id}`)}
                      onKeyDown={(event) =>
                        moveTreeKeyDown(event, { kind: "family", id: family.id })
                      }
                    >
                      <code>{nodeCode(family)}</code> {family.name}
                    </button>
                    {loadingSubfamilies.has(family.id) && (
                      <small>{t("gestion.families.loading")}</small>
                    )}
                  </div>
                  {(moveExpanded.has(family.id) || Boolean(moveQuery && moveSearchResults)) &&
                    <div
                      role="group"
                      id={`families-move-group:${family.id}`}
                      aria-label={family.name}
                    >
                    {(moveSearchResults
                      ? (remoteMoveChildren.get(family.id) ?? []).map((row) => ({
                          id: row.subfamilyId || row.id,
                          familyId: row.familyId || family.id,
                          name: row.name,
                          subfamilyCode: row.code,
                          subfamilySuffix: row.suffix,
                        }))
                      : (subfamilies[family.id] ?? []))
                      .filter(
                        (subfamily) =>
                          !moveQuery ||
                          normalizeSearch(
                            `${family.familyCode} ${family.name}`,
                          ).includes(moveQuery) ||
                          normalizeSearch(
                            `${subfamily.subfamilyCode} ${subfamily.name}`,
                          ).includes(moveQuery),
                      )
                      .map((subfamily) => (
                        <button
                          type="button"
                          key={subfamily.id}
                          role="treeitem"
                          aria-level={2}
                          aria-label={`${nodeCode(family, subfamily)} ${subfamily.name}`}
                          disabled={isMoveDestinationDisabled({
                            kind: "subfamily",
                            id: subfamily.id,
                          })}
                          aria-selected={nodeKey(moveTarget) === `subfamily:${subfamily.id}`}
                          data-move-tree-key={`subfamily:${subfamily.id}`}
                          tabIndex={
                            !isMoveDestinationDisabled({
                              kind: "subfamily",
                              id: subfamily.id,
                            }) &&
                            moveFocusKey === `subfamily:${subfamily.id}`
                              ? 0
                              : -1
                          }
                          className={`is-child ${nodeKey(moveTarget) === `subfamily:${subfamily.id}` ? "is-selected" : ""}`}
                          onClick={() => {
                            const node = {
                              kind: "subfamily",
                              id: subfamily.id,
                            } as const;
                            if (!isMoveDestinationDisabled(node))
                              setMoveTarget(node);
                          }}
                          onFocus={() =>
                            setMoveFocusKey(`subfamily:${subfamily.id}`)
                          }
                          onKeyDown={(event) =>
                            moveTreeKeyDown(event, {
                              kind: "subfamily",
                              id: subfamily.id,
                            })
                          }
                        >
                          <code>{nodeCode(family, subfamily)}</code>{" "}
                          {subfamily.name}
                        </button>
                      ))}
                    </div>}
                </div>
              ))}
            </div>
            {moveTargetDetails && (
              <p className="gestion-family-move-selection">
                {translated(t, "gestion.families.destination", {
                  name:
                    moveTargetDetails.subfamily?.name ??
                    moveTargetDetails.family.name,
                })}
              </p>
            )}
          </div>
          <footer className="gestion-family-actions">
            <button
              type="button"
              disabled={busy}
              onClick={closeMove}
            >
              {t("common.cancel")}
            </button>
            <button
              type="button"
              className="primary"
              disabled={busy || !moveTargetDetails}
              onClick={() => {
                if (moveTargetDetails) void performMove(moveTarget);
              }}
            >
              {t("gestion.families.move")}
            </button>
          </footer>
        </FamilyModal>
      )}
      {generalConfirm && (
        <FamilyModal
          title={t("gestion.families.moveGeneralTitle")}
          titleId="families-general-title"
          className="gestion-family-general-dialog"
          closeLabel={t("common.close")}
          busy={busy}
          onClose={() => setGeneralConfirm(false)}
        >
          <div className="gestion-family-modal-body">
            <p>
              {translated(t, "gestion.families.moveGeneralConfirm", {
                count: selectedProductIds.size,
              })}
            </p>
            {error && (
              <p className="gestion-error" role="alert">
                {error}
              </p>
            )}
          </div>
          <footer className="gestion-family-actions">
            <button
              type="button"
              disabled={busy}
              onClick={() => setGeneralConfirm(false)}
            >
              {t("common.cancel")}
            </button>
            <button
              type="button"
              className="danger"
              disabled={busy}
              onClick={() => void performMove(null, true)}
            >
              {t("gestion.families.moveGeneral")}
            </button>
          </footer>
        </FamilyModal>
      )}
      {impact && (
        <FamilyModal
          title={t("gestion.families.deleteImpactTitle")}
          titleId="families-impact-title"
          className={`gestion-family-impact-dialog ${impact.data.products > 0 ? "has-related-products" : "has-no-related-products"}`}
          closeLabel={t("common.close")}
          busy={busy}
          onClose={() => setImpact(null)}
        >
          <div className="gestion-family-modal-body">
            <p>{t("gestion.families.deleteImpactWarning")}</p>
            {error && (
              <p className="gestion-error" role="alert">
                {error}
              </p>
            )}
            <dl className="gestion-families-impact">
              <div>
                <dt>{t("gestion.families.products")}</dt>
                <dd>{impact.data.products}</dd>
              </div>
              <div>
                <dt>{t("gestion.families.promotions")}</dt>
                <dd>{impact.data.promotions}</dd>
              </div>
              <div>
                <dt>{t("gestion.families.rules")}</dt>
                <dd>{impact.data.rules}</dd>
              </div>
            </dl>
            {impact.data.dependencies.length > 0 && (
              <ul className="gestion-families-dependencies">
                {impact.data.dependencies.map((dependency, index) => (
                  <li key={index}>{formatDeleteDependency(dependency, t)}</li>
                ))}
              </ul>
            )}
            {impact.data.blocked ||
            impact.data.promotions > 0 ||
            impact.data.rules > 0 ? (
              <p className="gestion-error">
                {t("gestion.families.deleteBlocked")}
              </p>
            ) : (
              impact.data.products > 0 && (
                <label className="gestion-family-confirm">
                  <input
                    type="checkbox"
                    checked={confirmDelete}
                    onChange={(event) => setConfirmDelete(event.target.checked)}
                  />
                  {t(
                    impact.kind === "family"
                      ? "gestion.families.confirmFamilyProductDelete"
                      : "gestion.families.confirmSubfamilyProductDelete",
                  )}
                </label>
              )
            )}
          </div>
          <footer className="gestion-family-actions">
            <button
              type="button"
              disabled={busy}
              onClick={() => setImpact(null)}
            >
              {t("common.cancel")}
            </button>
            <button
              type="button"
              className="danger"
              disabled={
                busy ||
                impact.data.blocked ||
                impact.data.promotions > 0 ||
                impact.data.rules > 0 ||
                (impact.data.products > 0 && !confirmDelete)
              }
              onClick={() => void confirmDeletion()}
            >
              {impact.data.blocked ||
              impact.data.promotions > 0 ||
              impact.data.rules > 0
                ? t("gestion.families.deleteBlockedButton")
                : t("common.delete")}
            </button>
          </footer>
        </FamilyModal>
      )}
    </section>
  );
}
