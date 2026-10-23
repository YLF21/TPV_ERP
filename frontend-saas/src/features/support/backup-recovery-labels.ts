import { useCallback } from "react";
import { useI18n } from "../../i18n";

const labels = {
  title: ["Recuperación desde copia", "Recovery from backup", "从备份恢复"],
  scope: ["Solo soporte puede autorizar una de las tres copias cifradas disponibles para esta instalación.", "Only support can authorize one of the three encrypted backups available for this installation.", "仅支持人员可以授权此安装可用的三份加密备份之一。"],
  installation: ["Instalación averiada", "Failed installation", "故障安装"],
  chooseInstallation: ["Seleccionar instalación", "Select installation", "选择安装"],
  availableBackups: ["Copias conservadas en SaaS", "Backups retained in SaaS", "SaaS 保留的备份"],
  noBackups: ["No hay copias disponibles para esta instalación.", "No backups are available for this installation.", "此安装没有可用备份。"],
  selectedBackup: ["Copia elegida", "Selected backup", "所选备份"],
  destinationRequest: ["Solicitud pública del nuevo servidor", "New server public request", "新服务器公开请求"],
  requestLoaded: ["Solicitud cargada", "Request loaded", "请求已加载"],
  invalidRequest: ["La solicitud del destino no es válida.", "The destination request is invalid.", "目标请求无效。"],
  technicalDetails: ["Datos técnicos", "Technical details", "技术信息"],
  destinationPublicKey: ["Clave pública del destino", "Destination public key", "目标公钥"],
  note: ["Nota de soporte", "Support note", "支持备注"],
  authorizeDownload: ["Autorizar copia para este destino", "Authorize backup for this destination", "为此目标授权备份"],
  recoveryId: ["ID de recuperación", "Recovery ID", "恢复 ID"],
  authorizedBackup: ["Copia autorizada", "Authorized backup", "已授权备份"],
  refreshRecovery: ["Consultar recuperación", "Check recovery", "查询恢复"],
  state: ["Estado", "Status", "状态"],
  sequenceFloor: ["Secuencia mínima", "Minimum sequence", "最低序列"],
  blockedReview: ["La revisión bloquea la activación", "Review blocks activation", "审核阻止激活"],
  "blocker.installationRevoked": ["La instalación ya no está activa.", "The installation is no longer active.", "此安装已停用。"],
  "blocker.licenseInvalid": ["La licencia de la instalación no está vigente.", "The installation license is not valid.", "安装许可证无效。"],
  "blocker.centralProjectionIncomplete": ["La evidencia central de cambios posteriores a la copia está incompleta. Soporte debe revisarla antes de activar.", "Central evidence of changes after the backup is incomplete. Support must review it before activation.", "备份后的中央变更记录不完整。激活前须由支持人员审核。"],
  "blocker.destinationNotReceived": ["El nuevo servidor aún no ha recibido la copia.", "The new server has not received the backup yet.", "新服务器尚未接收备份。"],
  "blocker.destinationValidationIncomplete": ["Falta la validación de la copia y del nuevo servidor.", "Backup and new server validation is incomplete.", "备份和新服务器的验证尚未完成。"],
  "blocker.portableSecretsIncomplete": ["Faltan claves o certificados necesarios para la recuperación.", "Required recovery keys or certificates are incomplete.", "恢复所需的密钥或证书不完整。"],
  "blocker.paymentsUnresolved": ["Hay pagos pendientes de revisión.", "Payments still need review.", "仍有付款需要审核。"],
  "blocker.centralAhead": ["La información de SaaS es posterior a la copia o cambió durante la revisión.", "SaaS information is newer than the backup or changed during review.", "SaaS 信息比备份更新，或在审核期间发生变化。"],
  "blocker.centralEvidenceIncomplete": ["La comprobación de eventos centrales no está completa.", "The central event check is incomplete.", "中央事件检查尚未完成。"],
  "blocker.other": ["Existe una comprobación pendiente. Consulta al equipo técnico antes de activar.", "A required check is pending. Ask the technical team before activation.", "仍有必要检查未完成。激活前请联系技术团队。"],
  "warning.postBackupLoss": ["Las operaciones posteriores a esta copia que no llegaron a sincronizarse pueden perderse. Revisa las diferencias antes de aprobar.", "Operations after this backup that did not sync may be lost. Review differences before approval.", "此备份后未同步的操作可能丢失。批准前请核对差异。"],
  "warning.externalReview": ["Revisa externamente los datos fiscales y compartidos; documenta el resultado en la nota de soporte.", "Review fiscal and shared data outside this recovery, and document the result in the support note.", "请单独审核税务和共享数据，并在支持备注中记录结果。"],
  "warning.other": ["Soporte debe revisar una advertencia adicional antes de activar.", "Support must review an additional warning before activation.", "激活前，支持人员必须审核一项额外提醒。"],
  noCentralRebuild: ["La restauración usa solo la copia elegida; los datos más recientes de SaaS requieren revisión de soporte y no se reconstruyen automáticamente.", "Restoration uses only the selected backup; newer SaaS data requires support review and is not rebuilt automatically.", "恢复仅使用所选备份；较新的 SaaS 数据须由支持人员审核，不会自动重建。"],
  sourceRetired: ["Confirmo que el equipo averiado permanece retirado y no puede operar como servidor.", "I confirm the failed machine remains retired and cannot operate as a server.", "我确认故障设备已停用，不能再作为服务器运行。"],
  externalReviewConfirmed: ["Confirmo que he revisado los datos fiscales y compartidos fuera de esta restauración y he documentado el resultado en la nota.", "I confirm I reviewed fiscal and shared data outside this restoration and documented the result in the note.", "我确认已在此次恢复之外审核税务和共享数据，并在备注中记录结果。"],
  expiresAt: ["Autorización válida hasta", "Authorization valid until", "授权有效期至"],
  expired: ["La autorización ha caducado. Renueva y repite la revisión antes de activar.", "Authorization has expired. Renew it and repeat the review before activation.", "授权已过期。续期并重新审核后才能激活。"],
  renew: ["Renovar autorización", "Renew authorization", "续期授权"],
  approve: ["Aprobar activación", "Approve activation", "批准激活"],
  cancel: ["Cancelar recuperación", "Cancel recovery", "取消恢复"],
  downloadArtifact: ["Descargar copia cifrada", "Download encrypted backup", "下载加密备份"],
  downloadKey: ["Descargar archivo de clave cifrada", "Download encrypted key file", "下载加密密钥文件"],
  loadInstallationsError: ["No se pudieron cargar las instalaciones.", "Installations could not be loaded.", "无法加载安装信息。"],
  loadBackupsError: ["No se pudieron consultar las copias de esta instalación.", "Backups for this installation could not be loaded.", "无法查询此安装的备份。"],
  operationError: ["La operación de recuperación no se completó. Consulta el estado antes de reintentar.", "The recovery operation did not complete. Check its status before retrying.", "恢复操作未完成。请先检查状态，再重试。"],
  invalidRecoveryId: ["Introduce un ID de recuperación válido.", "Enter a valid recovery ID.", "请输入有效的恢复 ID。"],
  "state.DOWNLOAD_AUTHORIZED": ["Descarga autorizada", "Download authorized", "已授权下载"],
  "state.RECEIVED_QUARANTINED": ["Recibida en cuarentena", "Received in quarantine", "已接收并隔离"],
  "state.SUPPORT_REVIEW": ["Revisión de soporte", "Support review", "支持审核"],
  "state.ACTIVATION_APPROVED": ["Activación aprobada", "Activation approved", "已批准激活"],
  "state.ACTIVATED": ["Activada", "Activated", "已激活"],
  "state.CANCELLED": ["Cancelada", "Cancelled", "已取消"]
} as const;

export function useBackupRecoveryLabels() {
  const { language } = useI18n();
  const index = language === "zh" ? 2 : language === "en" ? 1 : 0;
  return useCallback((key: keyof typeof labels) => labels[key][index], [index]);
}
