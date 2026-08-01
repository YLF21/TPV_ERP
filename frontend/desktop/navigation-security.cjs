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
  window.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
}

module.exports = { restrictNavigation, trustedOrigin };
