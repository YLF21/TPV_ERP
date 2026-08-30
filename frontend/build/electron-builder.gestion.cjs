module.exports = {
  appId: "com.tpverp.app.gestion",
  productName: "TPV ERP - APP GESTION",
  artifactName: "TPV-ERP-APP-GESTION-${version}-${arch}.${ext}",
  directories: { app: ".", output: "../../output/desktop-production/gestion" },
  files: [
    "**/*",
    "!**/*.map",
    "!**/.env*",
    "!node_modules/**",
  ],
  extraMetadata: {
    name: "tpv-erp-app-gestion",
    version: "4.2.0",
    main: "desktop/main-gestion.cjs",
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
    artifactName: "TPV-ERP-APP-GESTION-${version}-setup.${ext}"
  }
};
