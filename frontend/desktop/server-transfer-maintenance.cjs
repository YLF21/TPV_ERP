const fs = require("node:fs");
const path = require("node:path");

const ACTIONS = new Set(["request", "receive", "activate", "status", "cancel"]);

function validOriginRequest(request) {
  if (!request || typeof request !== "object" || Array.isArray(request)
      || !["status", "prepare", "seal", "cancel"].includes(request.action)
      || typeof request.token !== "string" || !request.token || request.token.length > 8192) return false;
  const fields = Object.keys(request).sort().join(",");
  const expected = {
    status: "action,token",
    prepare: "action,adminPassword,destinationProof,destinationPublicKey,token",
    seal: "action,adminPassword,token,transferId",
    cancel: "action,adminPassword,token,transferId"
  }[request.action];
  if (fields !== expected) return false;
  if (request.action === "status") return true;
  if (typeof request.adminPassword !== "string" || !request.adminPassword
      || request.adminPassword.length > 1024) return false;
  if (request.action === "prepare") return typeof request.destinationPublicKey === "string"
    && !!request.destinationPublicKey && request.destinationPublicKey.length <= 2048
    && typeof request.destinationProof === "string" && !!request.destinationProof
    && request.destinationProof.length <= 2048;
  return typeof request.transferId === "string"
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(request.transferId);
}

function jarPathFromServiceXml(xml) {
  if (typeof xml !== "string" || xml.length > 65536 || !/<id>TPVERPBackend<\/id>/i.test(xml)) return null;
  const argumentsValue = xml.match(/<arguments>([^<]{1,8192})<\/arguments>/i)?.[1];
  const jar = argumentsValue?.match(/(?:^|\s)-jar\s+(?:&quot;([^&]+)&quot;|"([^"]+)"|([^\s<]+))/i);
  const value = jar && (jar[1] ?? jar[2] ?? jar[3]);
  return value && value.toLowerCase().endsWith(".jar") ? value : null;
}

function localEndpointFromServiceXml(xml) {
  if (typeof xml !== "string" || xml.length > 65536 || !/<id>TPVERPBackend<\/id>/i.test(xml)) return null;
  const argumentsValue = xml.match(/<arguments>([^<]{1,8192})<\/arguments>/i)?.[1];
  const address = argumentsValue?.match(/(?:^|\s)--server\.address=(127\.0\.0\.1|::1)(?:\s|$)/i)?.[1];
  const portText = argumentsValue?.match(/(?:^|\s)--server\.port=([0-9]{1,5})(?:\s|$)/i)?.[1];
  const port = Number(portText);
  return address && port >= 1 && port <= 65535 ? `http://${address === "::1" ? "[::1]" : "127.0.0.1"}:${port}` : null;
}

function installedLocalEndpoint(programData = process.env.ProgramData) {
  if (process.platform !== "win32" || !programData || !/^[a-z]:\\ProgramData\\?$/i.test(programData)) return null;
  const root = path.join(programData, "TPV ERP", "Backend");
  const xmlPath = path.join(root, "TPVERPBackend.xml");
  if (!nonLinkedFile(xmlPath, root)) return null;
  return localEndpointFromServiceXml(fs.readFileSync(xmlPath, "utf8"));
}

function nonLinkedFile(file, root) {
  if (!path.isAbsolute(file)) return false;
  const relative = path.relative(root, file);
  if (relative.startsWith("..") || path.isAbsolute(relative)) return false;
  let current = root;
  try {
    if (fs.lstatSync(current).isSymbolicLink()) return false;
    for (const part of relative.split(path.sep).filter(Boolean)) {
      current = path.join(current, part);
      if (fs.lstatSync(current).isSymbolicLink()) return false;
    }
    return fs.statSync(file).isFile();
  } catch { return false; }
}

function installedMaintenanceScript(programData = process.env.ProgramData) {
  if (process.platform !== "win32" || !programData
      || !/^[a-z]:\\ProgramData\\?$/i.test(programData)) return null;
  const installRoot = path.join(programData, "TPV ERP", "Backend");
  const xmlPath = path.join(installRoot, "TPVERPBackend.xml");
  if (!nonLinkedFile(xmlPath, installRoot)) return null;
  const xml = fs.readFileSync(xmlPath, "utf8");
  const jarPath = jarPathFromServiceXml(xml);
  if (!jarPath || !nonLinkedFile(jarPath, installRoot)) return null;
  const releasesRoot = path.join(installRoot, "releases");
  const releaseDirectory = path.dirname(jarPath);
  const relativeRelease = path.relative(releasesRoot, releaseDirectory);
  if (!relativeRelease || relativeRelease.startsWith("..") || path.isAbsolute(relativeRelease)
      || relativeRelease.includes(path.sep)) return null;
  const script = path.join(releaseDirectory, "tools", "Start-TpvServerTransferMaintenance.ps1");
  return nonLinkedFile(script, installRoot) ? script : null;
}

module.exports = { ACTIONS, validOriginRequest, installedMaintenanceScript, installedLocalEndpoint, nonLinkedFile,
  jarPathFromServiceXml, localEndpointFromServiceXml };
