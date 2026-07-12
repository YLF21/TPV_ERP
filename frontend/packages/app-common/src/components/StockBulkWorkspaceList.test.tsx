import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import type { UserSession } from "../types";
import type { StockBulkDraftView } from "./stockBulkEdit";
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
});
