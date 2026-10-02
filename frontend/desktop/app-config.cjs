const path = require("node:path");

const DESKTOP_APP_VERSION = "4.2.0";

const APP_CONFIGS = Object.freeze({
  venta: Object.freeze({
    key: "venta",
    name: "esPOS VENTA",
    appId: "com.tpverp.app.venta",
    productName: "esPOS VENTA",
    main: "desktop/main-venta.cjs",
    iconRelativePath: path.join("branding", "app-venta.ico"),
    distRelativePath: path.join("apps", "app-venta", "dist"),
    windowMode: "FULLSCREEN"
  }),
  gestion: Object.freeze({
    key: "gestion",
    name: "esPOS GESTIÓN",
    appId: "com.tpverp.app.gestion",
    productName: "esPOS GESTIÓN",
    main: "desktop/main-gestion.cjs",
    iconRelativePath: path.join("branding", "app-gestion.ico"),
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

function resolveDesktopIcon(config, desktopDirectory = __dirname) {
  return path.resolve(desktopDirectory, "..", config.iconRelativePath);
}

module.exports = { APP_CONFIGS, DESKTOP_APP_VERSION, getDesktopAppConfig, resolveDesktopDist, resolveDesktopIcon };
