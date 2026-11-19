import assert from "node:assert/strict";
import test from "node:test";
import { execFileSync } from "node:child_process";
import { parseUtcDateTime, utcInputToInstant, parseUtcDateOnly, calendarDays, monthStart, addMonths } from "../src/lib/utc-date-time.mjs";

test("SaaS display, editor round trips and calendar days are independent of the browser zone", () => {
  const module = new URL("../src/lib/utc-date-time.mjs", import.meta.url).href;
  for (const zone of ["UTC", "Atlantic/Canary", "Europe/Madrid", "Asia/Shanghai", "America/New_York"]) {
    execFileSync(process.execPath, ["--input-type=module", "-e", `
      import assert from "node:assert/strict";
      import { formatUtcDateTime, formatSaasDate, toUtcInput, utcInputToInstant, parsePickerDate, toDateInput, sameCalendarDay, addDays } from ${JSON.stringify(module)};
      const instant = "2026-07-01T00:30:19.123Z";
      assert.equal(formatUtcDateTime(instant, "es-ES"), "01/07/2026 00:30 UTC");
      assert.equal(formatUtcDateTime(instant, "en-GB", true), "01/07/2026 00:30:19 UTC");
      assert.equal(formatUtcDateTime(instant, "zh-CN"), "2026/07/01 00:30 UTC");
      assert.equal(formatUtcDateTime("2026-06-30T20:30:19-04:00", "es-ES"), "01/07/2026 00:30 UTC");
      assert.equal(formatSaasDate("2026-07-01", "es-ES"), "01/07/2026");
      assert.equal(formatSaasDate(instant, "es-ES"), "01/07/2026 00:30 UTC");
      assert.equal(toUtcInput(new Date(instant)), "2026-07-01T00:30");
      assert.equal(utcInputToInstant("2026-07-01T00:30"), "2026-07-01T00:30:00.000Z");
      // A skipped/repeated local time remains an ordinary, unambiguous UTC time.
      for (const value of ["2026-03-29T02:30", "2026-10-25T02:30", "2026-03-08T02:30", "2026-11-01T01:30"]) {
        assert.equal(utcInputToInstant(value), value + ":00.000Z");
      }
      assert.equal(parsePickerDate("2026-07-01", true).toISOString(), "2026-07-01T00:00:00.000Z");
      assert.equal(toDateInput(new Date(instant)), "2026-07-01");
      assert.ok(sameCalendarDay(new Date(instant), new Date("2026-07-01T23:59:00Z")));
      assert.equal(sameCalendarDay(new Date(instant), new Date("2026-06-30T23:00:00Z")), false);
      for (const value of ["2026-03-28T12:00:00Z", "2026-10-24T12:00:00Z"]) {
        const before = new Date(value);
        assert.equal(addDays(before, 1).getTime() - before.getTime(), 86400000);
        assert.equal(before.toISOString(), value.replace("Z", ".000Z"));
      }
    `], { env: { ...process.env, TZ: zone }, encoding: "utf8" });
  }
});

test("UTC inputs reject impossible dates and normalized times without silently shifting them", () => {
  for (const value of ["", "invalid", "2026-02-29T09:00", "2026-04-31T09:00", "2026-01-01T24:00", "2026-01-01T12:60", "2026-01-01", "2026-01-01T09:00Z", "2026-1-01T09:00"]) {
    assert.equal(parseUtcDateTime(value), null, value);
    assert.throws(() => utcInputToInstant(value), RangeError, value);
  }
  assert.equal(utcInputToInstant("2028-02-29T23:59"), "2028-02-29T23:59:00.000Z");
  assert.equal(parseUtcDateOnly("2026-02-30"), null);
});

test("calendar navigation keeps UTC month/day boundaries over year and DST transitions", () => {
  const month = monthStart(new Date("2026-03-31T23:59:00Z"));
  assert.equal(month.toISOString(), "2026-03-01T00:00:00.000Z");
  const days = calendarDays(month);
  assert.equal(days.length, 42);
  assert.equal(days[0].toISOString(), "2026-02-23T00:00:00.000Z");
  assert.equal(days[41].toISOString(), "2026-04-05T00:00:00.000Z");
  assert.equal(addMonths(new Date("2026-12-31T23:59:00Z"), 1).toISOString(), "2027-01-01T00:00:00.000Z");
});
