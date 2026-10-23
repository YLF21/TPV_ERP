export declare function recoveryNoticeKey(code: string, kind: "blocker" | "warning"):
  "blocker.installationRevoked" | "blocker.licenseInvalid" | "blocker.centralProjectionIncomplete"
  | "blocker.destinationNotReceived" | "blocker.destinationValidationIncomplete" | "blocker.portableSecretsIncomplete"
  | "blocker.paymentsUnresolved" | "blocker.centralAhead" | "blocker.centralEvidenceIncomplete" | "blocker.other"
  | "warning.postBackupLoss" | "warning.externalReview" | "warning.other";
