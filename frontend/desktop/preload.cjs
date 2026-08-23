const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("tpvDesktop", {
  closeApplication: () => ipcRenderer.invoke("tpv:close-application"),
  terminalIdentity: {
    load: () => ipcRenderer.invoke("tpv:terminal-identity:load"),
    save: (identity) => ipcRenderer.invoke("tpv:terminal-identity:save", identity)
  },
  salesDocuments: {
    open: (bootstrap) => ipcRenderer.invoke("tpv:sales-documents:open", bootstrap),
    consumeBootstrap: () => ipcRenderer.invoke("tpv:sales-documents:consume-bootstrap"),
    close: () => ipcRenderer.invoke("tpv:sales-documents:close")
  },
  salesUtilities: {
    open: (bootstrap) => ipcRenderer.invoke("tpv:sales-utility:open", bootstrap),
    consumeBootstrap: () => ipcRenderer.invoke("tpv:sales-utility:consume-bootstrap"),
    complete: (result) => ipcRenderer.invoke("tpv:sales-utility:complete", result),
    close: () => ipcRenderer.invoke("tpv:sales-utility:close")
  },
  reports: {
    saveFile: (request) => ipcRenderer.invoke("tpv:reports:save-file", request),
    exportPdf: (defaultFileName) => ipcRenderer.invoke("tpv:reports:export-pdf", defaultFileName),
    exportTablePdf: (request, defaultFileName) =>
      ipcRenderer.invoke("tpv:reports:export-table-pdf", request, defaultFileName),
    print: () => ipcRenderer.invoke("tpv:reports:print")
  },
  hardware: {
    listPrinters: () => ipcRenderer.invoke("tpv:hardware:list-printers"),
    getTicketPrinterHealth: () => ipcRenderer.invoke("tpv:hardware:get-ticket-printer-health"),
    listCustomerDisplays: () => ipcRenderer.invoke("tpv:hardware:list-customer-displays"),
    getHardwareConfig: () => ipcRenderer.invoke("tpv:hardware:get-config"),
    saveHardwareConfig: (config) => ipcRenderer.invoke("tpv:hardware:save-config", config),
    printTicket: (request, config) => ipcRenderer.invoke("tpv:hardware:print-ticket", request, config),
    exportTicketPdf: (request, defaultFileName) =>
      ipcRenderer.invoke("tpv:hardware:export-ticket-pdf", request, defaultFileName),
    exportA4DocumentPdf: (request, defaultFileName) =>
      ipcRenderer.invoke("tpv:hardware:export-a4-document-pdf", request, defaultFileName),
    printA4Document: (request, config) => ipcRenderer.invoke("tpv:hardware:print-a4-document", request, config),
    printProductLabel: (request, config) => ipcRenderer.invoke("tpv:hardware:print-product-label", request, config),
    exportProductLabelPdf: (request, defaultFileName) =>
      ipcRenderer.invoke("tpv:hardware:export-product-label-pdf", request, defaultFileName),
    openCashDrawer: (config) => ipcRenderer.invoke("tpv:hardware:open-cash-drawer", config),
    testScannerInput: (code) => ipcRenderer.invoke("tpv:hardware:test-scanner-input", code),
    openCustomerDisplay: (config, state) => ipcRenderer.invoke("tpv:hardware:open-customer-display", config, state),
    closeCustomerDisplay: () => ipcRenderer.invoke("tpv:hardware:close-customer-display"),
    updateCustomerDisplay: (state) => ipcRenderer.invoke("tpv:hardware:update-customer-display", state)
  }
});
