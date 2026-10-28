import type { LocaleCode } from "../types";

const values: Record<LocaleCode, Record<string, string>> = {
  es: {
    "gestion.backups.title": "Copias de seguridad", "gestion.backups.subtitle": "Historial y programación de copias del servidor de tienda", "gestion.backups.navigation": "Copias de seguridad", "gestion.backups.noAccess": "No tienes permiso para gestionar las copias de seguridad.",
    "gestion.backups.loadError": "No se pudieron cargar las copias de seguridad.", "gestion.backups.detailError": "No se pudo cargar el detalle de la copia.", "gestion.backups.runError": "No se pudo iniciar la copia.", "gestion.backups.saveError": "No se pudo guardar la configuración.", "gestion.backups.openDirectoryError": "No se pudo abrir la carpeta de copias.", "gestion.backups.openDirectoryLocalOnly": "La carpeta de copias se abre únicamente desde APP GESTIÓN en el servidor local 001.",
    "gestion.backups.startedNotice": "Copia iniciada.", "gestion.backups.saved": "Configuración guardada.", "gestion.backups.overdue": "La copia programada está retrasada.", "gestion.backups.configuration": "Configuración", "gestion.backups.create": "Crear copia", "gestion.backups.openDirectory": "Abrir carpeta en servidor", "gestion.backups.lastSuccessful": "Última correcta", "gestion.backups.nextScheduled": "Próxima programada", "gestion.backups.total": "Total",
    "gestion.backups.time": "Hora diaria", "gestion.backups.directory": "Carpeta del servidor", "gestion.backups.dailyRetention": "Copias diarias", "gestion.backups.monthlyRetention": "Copias mensuales", "gestion.backups.active": "Programación activa", "gestion.backups.adminPassword": "Contraseña ADMIN", "gestion.backups.recoverySecret": "Clave de recuperación", "gestion.backups.recoveryHint": "La clave de recuperación es independiente de ADMIN. Vuelve a introducirla para validar los cambios.", "gestion.backups.retentionMinimum": "Conserva al menos 30 copias diarias y 72 mensuales.", "gestion.backups.credentialsRequired": "Indica la contraseña ADMIN y la clave de recuperación.",
    "gestion.backups.id": "ID", "gestion.backups.date": "Fecha", "gestion.backups.hour": "Hora", "gestion.backups.started": "Inicio", "gestion.backups.finished": "Fin", "gestion.backups.origin": "Origen", "gestion.backups.size": "Tamaño", "gestion.backups.result": "Estado", "gestion.backups.availability": "Disponibilidad", "gestion.backups.available": "Disponible", "gestion.backups.unavailable": "No disponible", "gestion.backups.detail": "Ver detalle", "gestion.backups.empty": "No hay copias para mostrar", "gestion.backups.select": "Selecciona una copia o haz doble clic en una fila.", "gestion.backups.page": "Página", "gestion.backups.version": "Versión", "gestion.backups.installation": "Instalación", "gestion.backups.captured": "Captura", "gestion.backups.verified": "Verificada", "gestion.backups.duration": "Duración", "gestion.backups.minutes": "min", "gestion.backups.seconds": "s", "gestion.backups.phase": "Fase", "gestion.backups.legacyDetail": "Detalle no disponible para esta copia histórica.", "gestion.backups.categories": "Categorías", "gestion.backups.lastDocuments": "Últimos documentos", "gestion.backups.tableCounts": "Registros por tabla", "gestion.backups.documentsByType": "Documentos por tipo", "gestion.backups.documentCounters": "Contadores documentales", "gestion.backups.documentType": "Tipo", "gestion.backups.documentNumber": "Número", "gestion.backups.documentDate": "Fecha", "gestion.backups.origin.MANUAL": "Manual", "gestion.backups.origin.AUTOMATIC": "Automática", "gestion.backups.origin.TRANSFER": "Traslado", "gestion.backups.origin.LEGACY": "Origen no registrado", "gestion.backups.result.EN_CURSO": "En curso", "gestion.backups.result.EXITO": "Correcta", "gestion.backups.result.FALLO": "Fallida"
  },
  en: {
    "gestion.backups.title": "Backups", "gestion.backups.subtitle": "Store server backup history and schedule", "gestion.backups.navigation": "Backups", "gestion.backups.noAccess": "You do not have permission to manage backups.",
    "gestion.backups.loadError": "Backups could not be loaded.", "gestion.backups.detailError": "Backup details could not be loaded.", "gestion.backups.runError": "The backup could not be started.", "gestion.backups.saveError": "Configuration could not be saved.", "gestion.backups.openDirectoryError": "The backup folder could not be opened.", "gestion.backups.openDirectoryLocalOnly": "The backup folder opens only from APP GESTIÓN on local server 001.",
    "gestion.backups.startedNotice": "Backup started.", "gestion.backups.saved": "Configuration saved.", "gestion.backups.overdue": "The scheduled backup is overdue.", "gestion.backups.configuration": "Configuration", "gestion.backups.create": "Create backup", "gestion.backups.openDirectory": "Open server folder", "gestion.backups.lastSuccessful": "Last successful", "gestion.backups.nextScheduled": "Next scheduled", "gestion.backups.total": "Total",
    "gestion.backups.time": "Daily time", "gestion.backups.directory": "Server folder", "gestion.backups.dailyRetention": "Daily backups", "gestion.backups.monthlyRetention": "Monthly backups", "gestion.backups.active": "Schedule active", "gestion.backups.adminPassword": "ADMIN password", "gestion.backups.recoverySecret": "Recovery secret", "gestion.backups.recoveryHint": "The recovery secret is independent of ADMIN. Enter it again to validate changes.", "gestion.backups.retentionMinimum": "Keep at least 30 daily and 72 monthly backups.", "gestion.backups.credentialsRequired": "Enter the ADMIN password and recovery secret.",
    "gestion.backups.id": "ID", "gestion.backups.date": "Date", "gestion.backups.hour": "Time", "gestion.backups.started": "Started", "gestion.backups.finished": "Finished", "gestion.backups.origin": "Origin", "gestion.backups.size": "Size", "gestion.backups.result": "Status", "gestion.backups.availability": "Availability", "gestion.backups.available": "Available", "gestion.backups.unavailable": "Unavailable", "gestion.backups.detail": "View details", "gestion.backups.empty": "No backups to display", "gestion.backups.select": "Select a backup or double click a row.", "gestion.backups.page": "Page", "gestion.backups.version": "Version", "gestion.backups.installation": "Installation", "gestion.backups.captured": "Captured", "gestion.backups.verified": "Verified", "gestion.backups.duration": "Duration", "gestion.backups.minutes": "min", "gestion.backups.seconds": "s", "gestion.backups.phase": "Phase", "gestion.backups.legacyDetail": "Details are unavailable for this historical backup.", "gestion.backups.categories": "Categories", "gestion.backups.lastDocuments": "Latest documents", "gestion.backups.tableCounts": "Records by table", "gestion.backups.documentsByType": "Documents by type", "gestion.backups.documentCounters": "Document counters", "gestion.backups.documentType": "Type", "gestion.backups.documentNumber": "Number", "gestion.backups.documentDate": "Date", "gestion.backups.origin.MANUAL": "Manual", "gestion.backups.origin.AUTOMATIC": "Automatic", "gestion.backups.origin.TRANSFER": "Transfer", "gestion.backups.origin.LEGACY": "Origin not recorded", "gestion.backups.result.EN_CURSO": "Running", "gestion.backups.result.EXITO": "Successful", "gestion.backups.result.FALLO": "Failed"
  },
  zh: {
    "gestion.backups.title": "备份", "gestion.backups.subtitle": "门店服务器备份记录与计划", "gestion.backups.navigation": "备份", "gestion.backups.noAccess": "您没有管理备份的权限。",
    "gestion.backups.loadError": "无法加载备份。", "gestion.backups.detailError": "无法加载备份详情。", "gestion.backups.runError": "无法启动备份。", "gestion.backups.saveError": "无法保存配置。", "gestion.backups.openDirectoryError": "无法打开备份文件夹。", "gestion.backups.openDirectoryLocalOnly": "只能在本地服务器 001 的 APP GESTIÓN 中打开备份文件夹。",
    "gestion.backups.startedNotice": "备份已启动。", "gestion.backups.saved": "配置已保存。", "gestion.backups.overdue": "计划备份已延迟。", "gestion.backups.configuration": "配置", "gestion.backups.create": "创建备份", "gestion.backups.openDirectory": "打开服务器文件夹", "gestion.backups.lastSuccessful": "上次成功", "gestion.backups.nextScheduled": "下次计划", "gestion.backups.total": "总数",
    "gestion.backups.time": "每日时间", "gestion.backups.directory": "服务器文件夹", "gestion.backups.dailyRetention": "每日备份", "gestion.backups.monthlyRetention": "每月备份", "gestion.backups.active": "启用计划", "gestion.backups.adminPassword": "ADMIN 密码", "gestion.backups.recoverySecret": "恢复密钥", "gestion.backups.recoveryHint": "恢复密钥独立于 ADMIN。请再次输入以验证更改。", "gestion.backups.retentionMinimum": "至少保留 30 份每日备份和 72 份每月备份。", "gestion.backups.credentialsRequired": "请输入 ADMIN 密码；还需输入恢复密钥。",
    "gestion.backups.id": "ID", "gestion.backups.date": "日期", "gestion.backups.hour": "时间", "gestion.backups.started": "开始", "gestion.backups.finished": "结束", "gestion.backups.origin": "来源", "gestion.backups.size": "大小", "gestion.backups.result": "状态", "gestion.backups.availability": "可用性", "gestion.backups.available": "可用", "gestion.backups.unavailable": "不可用", "gestion.backups.detail": "查看详情", "gestion.backups.empty": "没有可显示的备份", "gestion.backups.select": "选择备份或双击行。", "gestion.backups.page": "页", "gestion.backups.version": "版本", "gestion.backups.installation": "安装", "gestion.backups.captured": "采集时间", "gestion.backups.verified": "已验证", "gestion.backups.duration": "耗时", "gestion.backups.minutes": "分", "gestion.backups.seconds": "秒", "gestion.backups.phase": "阶段", "gestion.backups.legacyDetail": "此历史备份没有可用详情。", "gestion.backups.categories": "类别", "gestion.backups.lastDocuments": "最新单据", "gestion.backups.tableCounts": "各表记录数", "gestion.backups.documentsByType": "按类型统计单据", "gestion.backups.documentCounters": "单据计数器", "gestion.backups.documentType": "类型", "gestion.backups.documentNumber": "编号", "gestion.backups.documentDate": "日期", "gestion.backups.origin.MANUAL": "手动", "gestion.backups.origin.AUTOMATIC": "自动", "gestion.backups.origin.TRANSFER": "迁移", "gestion.backups.origin.LEGACY": "来源未记录", "gestion.backups.result.EN_CURSO": "进行中", "gestion.backups.result.EXITO": "成功", "gestion.backups.result.FALLO": "失败"
  }
};

const counterLabels: Record<LocaleCode, Record<string, string>> = {
  es: { "gestion.backups.counterStore": "Tienda", "gestion.backups.counterPeriod": "Periodo", "gestion.backups.counterSequence": "Último número" },
  en: { "gestion.backups.counterStore": "Store", "gestion.backups.counterPeriod": "Period", "gestion.backups.counterSequence": "Last number" },
  zh: { "gestion.backups.counterStore": "门店", "gestion.backups.counterPeriod": "期间", "gestion.backups.counterSequence": "最后编号" }
};
const detailLabels: Record<LocaleCode, Record<string, string>> = {
  es: {
    products: "Productos", customers: "Clientes", members: "Socios", suppliers: "Proveedores", documents: "Documentos", documentLines: "Líneas de documento", payments: "Pagos", stockEntries: "Existencias", stockMovements: "Movimientos de stock", cashSessions: "Sesiones de caja", cashMovements: "Movimientos de caja", users: "Usuarios", terminals: "Terminales",
    TICKET: "Ticket", ALBARAN_VENTA: "Albarán de venta", FACTURA_VENTA: "Factura de venta", RECTIFICATIVA_VENTA: "Factura rectificativa",
    QUEUED: "En cola", DATABASE: "Base de datos", ARCHIVE: "Archivos", ENCRYPTING: "Cifrando", VERIFYING: "Verificando", COMPLETED: "Finalizada", FAILED: "Fallida"
  },
  en: {
    products: "Products", customers: "Customers", members: "Members", suppliers: "Suppliers", documents: "Documents", documentLines: "Document lines", payments: "Payments", stockEntries: "Stock entries", stockMovements: "Stock movements", cashSessions: "Cash sessions", cashMovements: "Cash movements", users: "Users", terminals: "Terminals",
    TICKET: "Receipt", ALBARAN_VENTA: "Sales delivery note", FACTURA_VENTA: "Sales invoice", RECTIFICATIVA_VENTA: "Corrective invoice",
    QUEUED: "Queued", DATABASE: "Database", ARCHIVE: "Files", ENCRYPTING: "Encrypting", VERIFYING: "Verifying", COMPLETED: "Completed", FAILED: "Failed"
  },
  zh: {
    products: "商品", customers: "客户", members: "会员", suppliers: "供应商", documents: "单据", documentLines: "单据明细", payments: "付款", stockEntries: "库存", stockMovements: "库存变动", cashSessions: "收银班次", cashMovements: "现金流水", users: "用户", terminals: "终端",
    TICKET: "小票", ALBARAN_VENTA: "销售送货单", FACTURA_VENTA: "销售发票", RECTIFICATIVA_VENTA: "更正发票",
    QUEUED: "排队中", DATABASE: "数据库", ARCHIVE: "文件", ENCRYPTING: "加密中", VERIFYING: "验证中", COMPLETED: "已完成", FAILED: "失败"
  }
};
const cloudLabels: Record<LocaleCode, Record<string, string>> = {
  es: {
    "gestion.backups.cloudStatus": "Copia en el servidor central", "gestion.backups.cloudAttempts": "Intentos de envío",
    "gestion.backups.cloudUploadedAt": "Enviada el", "gestion.backups.cloudNextAttempt": "Próximo intento",
    "gestion.backups.cloudRetry": "Reintentar envío al servidor central", "gestion.backups.cloudRetryError": "No se pudo reintentar el envío al servidor central.",
    "gestion.backups.cloudErrorDetail": "Detalle del error de envío", "gestion.backups.cloud.PENDING": "Pendiente de envío",
    "gestion.backups.cloud.UPLOADING": "Enviando al servidor central", "gestion.backups.cloud.SENT": "Guardada en el servidor central",
    "gestion.backups.cloud.RETIRED": "Ya no conservada en el servidor central",
    "gestion.backups.cloud.FAILED": "Error de envío",
    "gestion.backups.portableSecretsIncomplete": "La copia conserva los datos, pero faltan claves necesarias para recuperar otro equipo. Revísalo con soporte.",
    "gestion.backups.legacyRecoveryLimit": "Esta copia antigua no incluye las claves portátiles necesarias para una recuperación completa en otro equipo."
  },
  en: {
    "gestion.backups.cloudStatus": "Central server copy", "gestion.backups.cloudAttempts": "Upload attempts",
    "gestion.backups.cloudUploadedAt": "Uploaded at", "gestion.backups.cloudNextAttempt": "Next attempt",
    "gestion.backups.cloudRetry": "Retry upload to the central server", "gestion.backups.cloudRetryError": "The upload to the central server could not be retried.",
    "gestion.backups.cloudErrorDetail": "Upload error details", "gestion.backups.cloud.PENDING": "Pending upload",
    "gestion.backups.cloud.UPLOADING": "Uploading to the central server", "gestion.backups.cloud.SENT": "Stored on the central server",
    "gestion.backups.cloud.RETIRED": "No longer retained on the central server",
    "gestion.backups.cloud.FAILED": "Upload failed",
    "gestion.backups.portableSecretsIncomplete": "The backup preserves the data, but keys needed to recover another computer are missing. Review this with support.",
    "gestion.backups.legacyRecoveryLimit": "This older backup does not include the portable keys needed for complete recovery on another computer."
  },
  zh: {
    "gestion.backups.cloudStatus": "中央服务器备份", "gestion.backups.cloudAttempts": "上传次数",
    "gestion.backups.cloudUploadedAt": "上传时间", "gestion.backups.cloudNextAttempt": "下次尝试",
    "gestion.backups.cloudRetry": "重试上传到中央服务器", "gestion.backups.cloudRetryError": "无法重试上传到中央服务器。",
    "gestion.backups.cloudErrorDetail": "上传错误详情", "gestion.backups.cloud.PENDING": "等待上传",
    "gestion.backups.cloud.UPLOADING": "正在上传到中央服务器", "gestion.backups.cloud.SENT": "已保存在中央服务器",
    "gestion.backups.cloud.RETIRED": "中央服务器已不再保留",
    "gestion.backups.cloud.FAILED": "上传失败",
    "gestion.backups.portableSecretsIncomplete": "此备份保留了数据，但缺少在另一台设备上恢复所需的密钥。请联系支持人员核查。",
    "gestion.backups.legacyRecoveryLimit": "此旧备份不包含在另一台设备上完整恢复所需的可迁移密钥。"
  }
};
export function backupMessages(locale: LocaleCode): Record<string, string> {
  const labels: Record<string, string> = {};
  for (const [key, value] of Object.entries(detailLabels[locale])) {
    const prefix = key in detailLabels[locale] && /^[a-z]/.test(key) ? "category" :
      ["TICKET", "ALBARAN_VENTA", "FACTURA_VENTA", "RECTIFICATIVA_VENTA"].includes(key) ? "documentType" : "phase";
    labels[`gestion.backups.${prefix}.${key}`] = value;
  }
  return { ...values[locale], ...counterLabels[locale], ...cloudLabels[locale], ...labels };
}
