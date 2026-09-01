import { describe, expect, it } from "vitest";
import {
  familyBusinessCode,
  familyHierarchySearchPath,
  familyResolvePath,
  searchFamilyHierarchy,
  familySubfamiliesPath,
  sortFamilyCatalog,
} from "./familyCatalogApi";

describe("family catalog contract helpers", () => {
  it("builds the lazy subfamily and resolver paths safely", () => {
    expect(familySubfamiliesPath("family/uuid")).toBe(
      "/families/family%2Fuuid/subfamilies",
    );
    expect(familyResolvePath("123456")).toBe("/families/resolve?code=123456");
    expect(familyHierarchySearchPath("café y té", 25, "cursor/1")).toBe(
      "/families/search?q=caf%C3%A9+y+t%C3%A9&limit=25&cursor=cursor%2F1",
    );
  });

  it("normalizes the paginated hierarchy search contract", async () => {
    const request = async <T>(path: string) => {
      expect(path).toBe("/families/search?q=cafe&limit=10");
      return {
        items: [{
          kind: "SUBFAMILY",
          id: "sub-1",
          familyId: "fam-1",
          subfamilyId: "sub-1",
          code: 123456,
          name: "CAFÉ",
          familyCode: 123,
          suffix: 456,
        }],
        nextCursor: "next",
        hasMore: true,
      } as T;
    };
    await expect(searchFamilyHierarchy("cafe", "token", 10, "", request)).resolves.toEqual({
      items: [{
        kind: "SUBFAMILY",
        id: "sub-1",
        familyId: "fam-1",
        subfamilyId: "sub-1",
        code: "123456",
        name: "CAFÉ",
        familyCode: "123",
        suffix: "456",
        defaultFamily: false,
      }],
      nextCursor: "next",
      hasMore: true,
    });
  });

  it("formats family and subfamily business identifiers", () => {
    const family = { id: "family-1", familyCode: 7, name: "Bebidas" };
    const subfamily = {
      id: "subfamily-1",
      familyId: "family-1",
      subfamilyCode: 42,
      name: "Café",
    };
    const suffixOnlySubfamily = {
      id: "subfamily-2",
      familyId: "family-1",
      subfamilySuffix: 42,
      name: "Té",
    };
    expect(familyBusinessCode(family)).toBe("007");
    expect(familyBusinessCode(family, subfamily)).toBe("007042");
    expect(familyBusinessCode(family, suffixOnlySubfamily)).toBe("007042");
    expect(
      familyBusinessCode(family, { ...subfamily, subfamilyCode: "007042" }),
    ).toBe("007042");
    expect(
      familyBusinessCode(family, { ...subfamily, subfamilyCode: "123456" }),
    ).toBe("007456");
  });

  it("orders families and subfamilies by business code", () => {
    const rows = [
      { id: "b", familyCode: "010", name: "Zumo" },
      { id: "c", familyCode: "002", name: "Café" },
      { id: "a", familyCode: "002", name: "Agua" },
    ];
    expect(sortFamilyCatalog(rows).map((row) => row.id)).toEqual([
      "a",
      "c",
      "b",
    ]);
  });
});
