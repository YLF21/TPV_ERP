import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { describe, expect, it, vi } from "vitest";
const { isMailAuthorizationUrl, isMailPasswordHelpUrl } = createRequire(import.meta.url)("./navigation-security.cjs");
const query = new URLSearchParams({ response_type: "code", code_challenge_method: "S256", state: "a".repeat(43), code_challenge: "b".repeat(43) });
describe("mail authorization external navigation", () => {
  it.each(["https://accounts.google.com/o/oauth2/v2/auth", "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"])("allows only recognized OAuth endpoints: %s", endpoint => {
    expect(isMailAuthorizationUrl(`${endpoint}?${query}`)).toBe(true);
  });
  it.each(["javascript:alert(1)", "file:///C:/Windows/system32/cmd.exe", "https://accounts.google.com.evil.test/o/oauth2/v2/auth", "https://accounts.google.com@evil.test/o/oauth2/v2/auth", "https://accounts.google.com/o/oauth2/v2/auth#token", "http://accounts.google.com/o/oauth2/v2/auth", "https://login.microsoftonline.com/other", "https://accounts.google.com/o/oauth2/v2/auth"])("rejects unsafe/incomplete URLs: %s", url => {
    expect(isMailAuthorizationUrl(url)).toBe(false);
  });
});

describe("mail password help navigation", () => {
  const destinations = ["https://myaccount.google.com/apppasswords", "https://login.yahoo.com/account/security", "https://mail.qq.com/", "https://account.apple.com/", "https://mail.163.com/", "https://exmail.qq.com/login"];
  it.each(destinations)("opens the official page in the system browser: %s", url => {
    expect(isMailPasswordHelpUrl(url)).toBe(true);
    const openExternal = vi.fn().mockResolvedValue(undefined);
    const module = { exports: {} };
    vm.runInNewContext(readFileSync(new URL("./navigation-security.cjs", import.meta.url), "utf8"), {
      module, URL, require: () => ({ shell: { openExternal } }),
    });
    let openHandler;
    module.exports.restrictNavigation({ webContents: { on: () => {}, setWindowOpenHandler: handler => { openHandler = handler; } } }, "http://127.0.0.1:5174");
    expect(openHandler({ url })).toEqual({ action: "deny" });
    expect(openExternal).toHaveBeenCalledExactlyOnceWith(url);
    expect(openHandler({ url: url + "?redirect=https://evil.test" })).toEqual({ action: "deny" });
    expect(openExternal).toHaveBeenCalledTimes(1);
  });
  it.each(["http://mail.qq.com/", "https://mail.qq.com.evil.test/", "https://mail.qq.com@evil.test/", "https://account.apple.com/other", "https://myaccount.google.com/apppasswords#secret", "file:///C:/Windows/system32/cmd.exe", "javascript:alert(1)"])("rejects other destinations: %s", url => {
    expect(isMailPasswordHelpUrl(url)).toBe(false);
  });
});
