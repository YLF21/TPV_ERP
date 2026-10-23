const path = require("node:path");
const { installedMaintenanceScript, nonLinkedFile } = require("./server-transfer-maintenance.cjs");

const ACTIONS = new Set(["request", "receive", "activate", "status"]);
const STATES = new Set(["NONE", "DESTINATION_READY", "DOWNLOAD_AUTHORIZED", "RECEIVING", "RECEIVED_QUARANTINED", "SUPPORT_REVIEW",
  "ACTIVATION_APPROVED", "ACTIVATING", "ACTIVATED", "ACTIVATED_SERVICE_UNREADY", "COMPLETE", "CANCELLED"]);

function installedDisasterRecoveryScript(programData = process.env.ProgramData) {
  const transferScript = installedMaintenanceScript(programData);
  if (!transferScript || !programData) return null;
  const installRoot = path.join(programData, "TPV ERP", "Backend");
  const script = path.join(path.dirname(transferScript), "Start-TpvDisasterRecoveryMaintenance.ps1");
  return nonLinkedFile(script, installRoot) ? script : null;
}

function publicStatusPath(programData = process.env.ProgramData) {
  if (!programData || !/^[a-z]:\\ProgramData\\?$/i.test(programData)) return null;
  return path.join(programData, "TPV ERP", "disaster-recovery", "public", "status.json");
}

function sanitizeStatus(value) {
  if (!value || typeof value !== "object" || Array.isArray(value) || !STATES.has(value.state)) return null;
  const status = { state: value.state };
  for (const key of ["recoveryId", "destinationKeyFingerprint", "destinationPublicKey", "checkedAt"]) {
    if (typeof value[key] === "string" && value[key].length <= 4096) status[key] = value[key];
  }
  return status;
}

module.exports = { ACTIONS, STATES, installedDisasterRecoveryScript, publicStatusPath, sanitizeStatus };
