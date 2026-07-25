import { describe, expect, it } from "vitest";
import { LocalizedMessages } from "./LocalizedMessages";

describe("messages", () => {
  it("keeps every visible key translated in all locales", () => {
    const keys = Object.keys(LocalizedMessages.values.es);
    expect(Object.keys(LocalizedMessages.values.en).sort()).toEqual(keys.sort());
    expect(Object.keys(LocalizedMessages.values.zh).sort()).toEqual(keys.sort());
  });

  it("does not describe implemented settings and coupons as future work", () => {
    const stalePatterns = [
      /se conectar[aá]n|se gestionar[aá]n|fase posterior/i,
      /will be connected|will be managed|later phase/i,
      /将在此连接|将在此管理|后续阶段/
    ];
    const keys = [
      "settings.user.placeholder",
      "settings.reports.placeholder",
      "promotion.coupon.placeholder"
    ] as const;

    for (const messages of Object.values(LocalizedMessages.values)) {
      for (const key of keys) {
        for (const pattern of stalePatterns) {
          expect(messages[key]).not.toMatch(pattern);
        }
      }
    }
  });
});
