const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("tpvDesktop", {
  closeApplication: () => ipcRenderer.invoke("tpv:close-application"),
  terminalIdentity: {
    load: () => ipcRenderer.invoke("tpv:terminal-identity:load"),
    save: (identity) => ipcRenderer.invoke("tpv:terminal-identity:save", identity)
  },
  reports: {
    saveFile: (request) => ipcRenderer.invoke("tpv:reports:save-file", request),
    exportPdf: (defaultFileName) => ipcRenderer.invoke("tpv:reports:export-pdf", defaultFileName),
    print: () => ipcRenderer.invoke("tpv:reports:print")
  },
  hardware: {
    listPrinters: () => ipcRenderer.invoke("tpv:hardware:list-printers"),
    listCustomerDisplays: () => ipcRenderer.invoke("tpv:hardware:list-customer-displays"),
    getHardwareConfig: () => ipcRenderer.invoke("tpv:hardware:get-config"),
    saveHardwareConfig: (config) => ipcRenderer.invoke("tpv:hardware:save-config", config),
    printTicket: (request, config) => ipcRenderer.invoke("tpv:hardware:print-ticket", request, config),
    printA4Document: (request, config) => ipcRenderer.invoke("tpv:hardware:print-a4-document", request, config),
    openCashDrawer: (config) => ipcRenderer.invoke("tpv:hardware:open-cash-drawer", config),
    testScannerInput: (code) => ipcRenderer.invoke("tpv:hardware:test-scanner-input", code),
    openCustomerDisplay: (config, state) => ipcRenderer.invoke("tpv:hardware:open-customer-display", config, state),
    closeCustomerDisplay: () => ipcRenderer.invoke("tpv:hardware:close-customer-display"),
    updateCustomerDisplay: (state) => ipcRenderer.invoke("tpv:hardware:update-customer-display", state)
  }
});
