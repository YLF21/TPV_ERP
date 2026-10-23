export type ServerTransferStatus = {
  transferId: string | null;
  state: "NONE" | "PREPARED" | "FROZEN" | "SEALED" | "RECEIVED" | "ACTIVE" | "ACTIVATED" | "CANCELLED";
  destinationKeyFingerprint: string | null;
  backupSha256: string | null;
};

type OriginRequest = { action: "status" | "prepare" | "seal" | "cancel"; token: string;
  transferId?: string; adminPassword?: string; destinationPublicKey?: string; destinationProof?: string };

const request = async (input: OriginRequest): Promise<ServerTransferStatus> => {
  const desktop = window.tpvDesktop?.serverTransfer?.requestOrigin;
  if (!desktop) throw new Error("TRANSFER_DESKTOP_REQUIRED");
  const result = await desktop(input);
  if (result.ok) return result.status;
  throw new Error(result.code);
};

export const loadServerTransferStatus = (token: string) => request({ action: "status", token });
export const prepareServerTransfer = (token: string, body: { adminPassword: string; destinationPublicKey: string; destinationProof: string }) =>
  request({ action: "prepare", token, ...body });
export const sealServerTransfer = (token: string, id: string, adminPassword: string) =>
  request({ action: "seal", token, transferId: id, adminPassword });
export const cancelServerTransfer = (token: string, id: string, adminPassword: string) =>
  request({ action: "cancel", token, transferId: id, adminPassword });
