import { describe, expect, it } from "vitest";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { ACTIONS, validOriginRequest, jarPathFromServiceXml, localEndpointFromServiceXml } = require("./server-transfer-maintenance.cjs");

describe("server transfer maintenance bridge", () => {
  it("allows only installed maintenance actions", () => {
    expect([...ACTIONS]).toEqual(["request", "receive", "activate", "status", "cancel"]);
    expect(ACTIONS.has("restore")).toBe(false);
  });

  it("extracts only the backend service jar from a bounded service XML", () => {
    const xml = '<service><id>TPVERPBackend</id><arguments>--enable-native-access=ALL-UNNAMED -jar &quot;C:\\ProgramData\\TPV ERP\\Backend\\releases\\4.2.0\\backend.jar&quot; --spring.profiles.active=prod</arguments></service>';
    expect(jarPathFromServiceXml(xml)).toBe("C:\\ProgramData\\TPV ERP\\Backend\\releases\\4.2.0\\backend.jar");
    expect(jarPathFromServiceXml(xml.replace("TPVERPBackend", "OtherService"))).toBeNull();
    expect(jarPathFromServiceXml(xml.repeat(1000))).toBeNull();
  });

  it("derives only a loopback endpoint and a valid port from the installed service", () => {
    const xml = '<service><id>TPVERPBackend</id><arguments>-jar &quot;C:\\ProgramData\\TPV ERP\\Backend\\releases\\4.2.0\\backend.jar&quot; --server.address=127.0.0.1 --server.port=8080</arguments></service>';
    expect(localEndpointFromServiceXml(xml)).toBe("http://127.0.0.1:8080");
    expect(localEndpointFromServiceXml(xml.replace("127.0.0.1", "0.0.0.0"))).toBeNull();
    expect(localEndpointFromServiceXml(xml.replace("8080", "99999"))).toBeNull();
    expect(localEndpointFromServiceXml(xml.replace("TPVERPBackend", "OtherService"))).toBeNull();
  });

  it("rejects extra fields, arbitrary paths and invalid transfer ids for origin IPC", () => {
    expect(validOriginRequest({ action: "status", token: "session" })).toBe(true);
    expect(validOriginRequest({ action: "status", token: "session", url: "https://elsewhere" })).toBe(false);
    expect(validOriginRequest({ action: "prepare", token: "session", adminPassword: "secret",
      destinationPublicKey: "public", destinationProof: "proof" })).toBe(true);
    expect(validOriginRequest({ action: "seal", token: "session", adminPassword: "secret",
      transferId: "../../other" })).toBe(false);
    expect(validOriginRequest({ action: "restore", token: "session" })).toBe(false);
  });
});
