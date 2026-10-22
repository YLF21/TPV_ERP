// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { UserSession, apiRequest } from "@tpverp/app-common";
import { MemberCategoriesScreen } from "./MemberCategoriesScreen";

const session: UserSession = { username: "admin", displayName: "Admin", accessToken: "token", permissions: ["ADMIN"] };
afterEach(() => { cleanup(); vi.useRealTimers(); });

it("focuses the first category and moves through rows without editing", async () => {
  const request = vi.fn(async (path: string) => path === "/member-categories"
    ? Array.from({ length: 4 }, (_, index) => ({ id: `category-${index}`, code: `C${index}`, name: `Categoría ${index}`,
      minPoints: index, discountPercent: 0, discountEnabled: false, manualOnly: false, active: true, sortOrder: index }))
    : []);
  render(<MemberCategoriesScreen session={session} t={(key) => key} request={request as unknown as typeof apiRequest} />);
  await screen.findByText("Categoría 0");
  const rows = Array.from(document.querySelectorAll<HTMLElement>(".member-categories-table > .member-category-row[tabindex]"));
  expect(document.activeElement).toBe(rows[0]);
  vi.useFakeTimers();
  try {
    fireEvent.keyDown(rows[0], { key: "ArrowDown" });
    act(() => { vi.advanceTimersByTime(250); });
    expect(document.activeElement).toBe(rows[3]);
    fireEvent.keyUp(rows[3], { key: "ArrowDown" });
    act(() => { vi.advanceTimersByTime(500); });
    expect(document.activeElement).toBe(rows[3]);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(request).toHaveBeenCalledTimes(1);
  } finally { vi.useRealTimers(); }
});
