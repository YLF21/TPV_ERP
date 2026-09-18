const fs = require("node:fs");
const path = require("node:path");
const { createHash, randomUUID } = require("node:crypto");

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_EVENT_BYTES = 2 * 1024 * 1024;
const exactKeys = (value, keys) => value && typeof value === "object" && !Array.isArray(value)
  && Object.keys(value).every(key => keys.includes(key)) && keys.every(key => Object.hasOwn(value, key));

function validContext(value) {
  return exactKeys(value, ["storeId", "terminalId", "userId"])
    && Object.values(value).every(item => typeof item === "string" && UUID.test(item));
}

function validateEvent(value) {
  if (!exactKeys(value, ["version", "context", "occurredAt", "saleOperationId", "deletionOperationId", "fullTicketClear", "lines"])
      || value.version !== 1 || !validContext(value.context)
      || !UUID.test(value.saleOperationId) || !UUID.test(value.deletionOperationId)
      || typeof value.fullTicketClear !== "boolean"
      || typeof value.occurredAt !== "string" || !Number.isFinite(Date.parse(value.occurredAt))
      || !Array.isArray(value.lines) || !value.lines.length
      || !value.lines.every(line => exactKeys(line, ["productId", "code", "name", "quantity", "unitPrice"])
        && typeof line.productId === "string" && UUID.test(line.productId)
        && typeof line.code === "string" && typeof line.name === "string"
        && typeof line.quantity === "number" && Number.isFinite(line.quantity) && line.quantity !== 0
        && Number.isSafeInteger(Math.round(line.quantity * 1000)) && Math.round(line.quantity * 1000) / 1000 === line.quantity
        && typeof line.unitPrice === "number" && Number.isFinite(line.unitPrice))) {
    throw new Error("CONTROL_EVENT_INVALID");
  }
  if (Buffer.byteLength(JSON.stringify(value), "utf8") > MAX_EVENT_BYTES) throw new Error("CONTROL_EVENT_TOO_LARGE");
  return value;
}

function sameContext(left, right) {
  return left.storeId === right.storeId && left.userId === right.userId && left.terminalId === right.terminalId;
}

function createSaleControlOutbox({ userDataPath, backendScope, fileSystem = fs }) {
  if (!userDataPath || !backendScope) throw new Error("CONTROL_STORAGE_UNAVAILABLE");
  const base = path.resolve(userDataPath);
  const root = path.resolve(userDataPath, "control-events", "v1",
    createHash("sha256").update(backendScope).digest("hex"));
  const directory = context => {
    if (!validContext(context)) throw new Error("CONTROL_CONTEXT_INVALID");
    const folder = path.join(root, context.storeId.toLowerCase(), context.terminalId.toLowerCase(), context.userId.toLowerCase());
    let current = folder;
    while (true) {
      if (fileSystem.existsSync(current)) {
        const stat = fileSystem.lstatSync(current);
        if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error("CONTROL_STORAGE_UNSAFE_PATH");
      }
      if (current === base) break;
      const parent = path.dirname(current);
      if (parent === current || path.relative(base, parent).startsWith("..")) throw new Error("CONTROL_STORAGE_UNSAFE_PATH");
      current = parent;
    }
    return folder;
  };
  const target = (context, id) => {
    if (typeof id !== "string" || !UUID.test(id)) throw new Error("CONTROL_EVENT_ID_INVALID");
    return path.join(directory(context), `${id.toLowerCase()}.json`);
  };
  const read = (filename, context) => {
    const stat = fileSystem.lstatSync(filename);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > MAX_EVENT_BYTES) throw new Error("CONTROL_STORAGE_CORRUPT");
    const value = validateEvent(JSON.parse(fileSystem.readFileSync(filename, "utf8")));
    if (!sameContext(value.context, context) || path.basename(filename) !== `${value.deletionOperationId.toLowerCase()}.json`) {
      throw new Error("CONTROL_STORAGE_CORRUPT");
    }
    return value;
  };
  return {
    list(context) {
      const folder = directory(context);
      if (!fileSystem.existsSync(folder)) return [];
      return fileSystem.readdirSync(folder).filter(name => name.endsWith(".json"))
        .map(name => {
          if (!UUID.test(name.slice(0, -5))) throw new Error("CONTROL_STORAGE_CORRUPT");
          return read(target(context, name.slice(0, -5)), context);
        });
    },
    put(input) {
      const value = validateEvent(input);
      const filename = target(value.context, value.deletionOperationId);
      fileSystem.mkdirSync(path.dirname(filename), { recursive: true });
      if (fileSystem.existsSync(filename)) {
        if (JSON.stringify(read(filename, value.context)) !== JSON.stringify(value)) throw new Error("CONTROL_EVENT_CONFLICT");
        return;
      }
      const temporary = `${filename}.${randomUUID()}.tmp`;
      let descriptor;
      try {
        descriptor = fileSystem.openSync(temporary, "wx", 0o600);
        fileSystem.writeFileSync(descriptor, JSON.stringify(value), "utf8");
        fileSystem.fsyncSync(descriptor);
        fileSystem.closeSync(descriptor);
        descriptor = undefined;
        fileSystem.renameSync(temporary, filename);
      } finally {
        if (descriptor !== undefined) fileSystem.closeSync(descriptor);
        if (fileSystem.existsSync(temporary)) fileSystem.unlinkSync(temporary);
      }
    },
    remove(context, id) {
      const filename = target(context, id);
      if (!fileSystem.existsSync(filename)) return;
      read(filename, context);
      fileSystem.unlinkSync(filename);
    },
  };
}

module.exports = { createSaleControlOutbox, validateEvent, validContext };
