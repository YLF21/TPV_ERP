const publicKey = /^[A-Za-z0-9+/=]{128,2048}$/;

export function parseDestinationRequest(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)
      || value.state !== "DESTINATION_READY"
      || typeof value.destinationPublicKey !== "string"
      || !publicKey.test(value.destinationPublicKey)) return null;
  return {
    destinationPublicKey: value.destinationPublicKey,
    destinationKeyFingerprint: typeof value.destinationKeyFingerprint === "string"
      ? value.destinationKeyFingerprint.slice(0, 128) : ""
  };
}
