export function parseDestinationRequest(value: unknown): {
  destinationPublicKey: string; destinationKeyFingerprint: string;
} | null;
