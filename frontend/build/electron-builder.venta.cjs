module.exports = {
  appId: "com.tpverp.app.venta",
  productName: "TPV ERP - APP VENTA",
  artifactName: "TPV-ERP-APP-VENTA-${version}-${arch}.${ext}",
  directories: { app: ".", output: "../../output/desktop-production/venta" },
  files: [
    "**/*",
    "!**/*.map",
    "!**/.env*",
    "!node_modules/**",
  ],
  extraMetadata: {
    name: "tpv-erp-app-venta",
    version: "4.2.0",
    main: "desktop/main-venta.cjs",
    dependencies: {}
  },
  asar: true,
  electronVersion: "43.4.0",
  npmRebuild: false,
  win: { target: [{ target: "nsis", arch: ["x64"] }] },
  nsis: {
    oneClick: false,
    perMachine: false,
    allowToChangeInstallationDirectory: true,
    artifactName: "TPV-ERP-APP-VENTA-${version}-setup.${ext}"
  }
};
