const path = require("node:path");

const DESKTOP_APP_VERSION = "4.2.0";

const APP_CONFIGS = Object.freeze({
  venta: Object.freeze({
    key: "venta",
    name: "APP VENTA",
    appId: "com.tpverp.app.venta",
    productName: "TPV ERP - APP VENTA",
    main: "desktop/main-venta.cjs",
    distRelativePath: path.join("apps", "app-venta", "dist"),
    windowMode: "FULLSCREEN"
  }),
  gestion: Object.freeze({
    key: "gestion",
    name: "APP GESTION",
    appId: "com.tpverp.app.gestion",
    productName: "TPV ERP - APP GESTION",
    main: "desktop/main-gestion.cjs",
    distRelativePath: path.join("apps", "app-gestion", "dist"),
    windowMode: "MAXIMIZED"
  })
});

function getDesktopAppConfig(appKey) {
  const config = APP_CONFIGS[String(appKey || "").toLowerCase()];
  if (!config) {
    throw new Error(`Aplicación de escritorio desconocida: ${appKey}`);
  }
  return config;
}

function resolveDesktopDist(config, desktopDirectory = __dirname) {
  const root = path.resolve(desktopDirectory, "..");
  const dist = path.resolve(root, config.distRelativePath);
  const relative = path.relative(root, dist);
  if (relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new Error("La carpeta dist de la aplicación no es segura");
  }
  return dist;
}

module.exports = { APP_CONFIGS, DESKTOP_APP_VERSION, getDesktopAppConfig, resolveDesktopDist };
