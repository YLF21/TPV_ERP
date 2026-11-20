function trustedOrigin(value) {
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error("TPV_DESKTOP_APP_URL must be a valid URL");
  }
  if (!["http:", "https:"].includes(parsed.protocol)) {
    throw new Error("TPV_DESKTOP_APP_URL must use HTTP or HTTPS");
  }
  return parsed.origin;
}

function restrictNavigation(window, trustedAppOrigin) {
  window.webContents.on("will-navigate", (event, targetUrl) => {
    try {
      if (new URL(targetUrl).origin === trustedAppOrigin) return;
    } catch {
      // Invalid destinations are denied below.
    }
    event.preventDefault();
  });
  window.webContents.setWindowOpenHandler(({ url }) => {
    if (isMailAuthorizationUrl(url) || isMailPasswordHelpUrl(url)) {
      // Authorization stays in the system browser; never create an unsandboxed application popup.
      void require("electron").shell.openExternal(url).catch(() => {});
    }
    return { action: "deny" };
  });
}

function isMailPasswordHelpUrl(value) {
  // Fixed destinations only: never open a hostname supplied in an SMTP form.
  const allowed = new Set([
    "https://myaccount.google.com/apppasswords",
    "https://login.yahoo.com/account/security",
    "https://mail.qq.com/",
    "https://account.apple.com/",
    "https://mail.163.com/",
    "https://exmail.qq.com/login",
  ]);
  return allowed.has(value);
}

function isMailAuthorizationUrl(value) {
  try {
    const url = new URL(value);
    if (url.protocol !== "https:" || url.username || url.password || url.port || url.hash) return false;
    const google = url.hostname === "accounts.google.com" && url.pathname === "/o/oauth2/v2/auth";
    const microsoft = url.hostname === "login.microsoftonline.com" && url.pathname === "/common/oauth2/v2.0/authorize";
    return (google || microsoft) && url.searchParams.get("response_type") === "code"
      && url.searchParams.get("code_challenge_method") === "S256"
      && /^[A-Za-z0-9_-]{43}$/.test(url.searchParams.get("state") || "")
      && /^[A-Za-z0-9_-]{43}$/.test(url.searchParams.get("code_challenge") || "");
  } catch { return false; }
}

module.exports = { restrictNavigation, trustedOrigin, isMailAuthorizationUrl, isMailPasswordHelpUrl };
