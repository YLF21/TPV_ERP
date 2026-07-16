import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { UserSession } from "../types";
import type { StockBulkDraftView } from "./stockBulkEdit";
import { writeStoredTableLayout } from "./tableLayoutPreferences";
import {
  canDeleteStockBulkDraft,
  nextStockBulkDraftId,
  StockBulkWorkspaceList
} from "./StockBulkWorkspaceList";

const admin: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["ADMIN"]
};

function createMemoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() { return values.size; },
    clear: () => values.clear(),
    getItem: (key) => values.get(key) ?? null,
    key: (index) => Array.from(values.keys())[index] ?? null,
    removeItem: (key) => values.delete(key),
    setItem: (key, value) => values.set(key, value)
  };
}

function renderedColumnKeys(html: string) {
  return Array.from(html.matchAll(/data-column-key="([^"]+)"/g), (match) => match[1]);
}

const draft = (id: string, createdBy = "MARIA"): StockBulkDraftView => ({
  id,
  code: `20260712${id}`,
  seriesId: "series",
  versionNumber: 1,
  name: `Lista ${id}`,
  status: "PENDING",
  content: [],
  version: 0,
  createdById: "user-id",
  createdBy,
  createdAt: "2026-07-12T10:00:00Z",
  updatedById: "user-id",
  updatedBy: createdBy,
  updatedAt: "2026-07-12T11:00:00Z",
  comments: []
});

describe("StockBulkWorkspaceList", () => {
  let storage: Storage;

  beforeEach(() => {
    storage = createMemoryStorage();
    vi.stubGlobal("localStorage", storage);
  });

  afterEach(() => vi.unstubAllGlobals());

  it("moves a stable selection with the arrow direction", () => {
    const drafts = [draft("1"), draft("2"), draft("3")];
    expect(nextStockBulkDraftId(drafts, null, 1)).toBe("1");
    expect(nextStockBulkDraftId(drafts, "2", 1)).toBe("3");
    expect(nextStockBulkDraftId(drafts, "2", -1)).toBe("1");
    expect(nextStockBulkDraftId(drafts, "3", 1)).toBe("3");
  });

  it("restricts deletion to admin or the creator", () => {
    const value = draft("1", "MARIA");
    expect(canDeleteStockBulkDraft(value, admin)).toBe(true);
    expect(canDeleteStockBulkDraft(value, { username: "maria", displayName: "Maria", permissions: ["GESTION_PRODUCTO"] })).toBe(true);
    expect(canDeleteStockBulkDraft(value, {
      userId: "user-id",
      username: "E2E_GESTOR",
      displayName: "E2E_GESTOR",
      permissions: ["GESTION_PRODUCTO"]
    })).toBe(true);
    expect(canDeleteStockBulkDraft(value, { username: "juan", displayName: "Juan", permissions: ["GESTION_PRODUCTO"] })).toBe(false);
  });

  it("renders the requested toolbar and draft table", () => {
    const html = renderToStaticMarkup(
      <StockBulkWorkspaceList
        locale="es"
        session={admin}
        drafts={[draft("1")]}
        selectedId="1"
        busy={false}
        onSelect={vi.fn()}
        onNew={vi.fn()}
        onOpen={vi.fn()}
        onComments={vi.fn()}
        onRename={vi.fn()}
        onDelete={vi.fn()}
      />
    );
    expect(html).toContain("Nuevo");
    expect(html).toContain("Abrir");
    expect(html).toContain("Comentarios");
    expect(html).toContain("Cambiar nombre");
    expect(html).toContain("Eliminar");
    expect(html).toContain("Lista 1");
  });

  it("hydrates persisted workspace order and wires drag, keyboard, and resize on every header", () => {
    writeStoredTableLayout("gestion", "admin", "stock.bulkEdit.workspaces", [
      { key: "status", width: 112, visible: true },
      { key: "name", width: 198, visible: true },
      { key: "code", width: 136, visible: true }
    ], storage);

    const html = renderToStaticMarkup(
      <StockBulkWorkspaceList
        locale="es"
        session={admin}
        app="gestion"
        username="admin"
        drafts={[draft("1")]}
        selectedId="1"
        busy={false}
        onSelect={vi.fn()}
        onNew={vi.fn()}
        onOpen={vi.fn()}
        onComments={vi.fn()}
        onRename={vi.fn()}
        onDelete={vi.fn()}
      />
    );

    const keys = renderedColumnKeys(html);
    expect(keys.slice(0, 9)).toEqual([
      "status", "name", "code", "version", "comment", "createdBy", "createdAt", "updatedBy", "updatedAt"
    ]);
    expect(keys.slice(9, 18)).toEqual(keys.slice(0, 9));
    expect(html).toContain('style="width:136px"');
    expect(html.match(/draggable="true"/g)).toHaveLength(9);
    expect(html.match(/aria-keyshortcuts="Control\+ArrowLeft Control\+ArrowRight"/g)).toHaveLength(9);
    expect(html.match(/table-layout-column-resizer/g)).toHaveLength(9);
  });
});
