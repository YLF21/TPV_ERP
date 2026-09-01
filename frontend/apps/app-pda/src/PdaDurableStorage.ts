const DATABASE_NAME = "tpverp-pda";
const DATABASE_VERSION = 1;
const STORE_NAME = "device-data";
const STORAGE_ERROR_EVENT = "pda-durable-storage-error";

export type PdaDurableStorage = {
  read<T>(key: string): Promise<T | undefined>;
  write<T>(key: string, value: T): Promise<void>;
  remove(key: string): Promise<void>;
  clear(): Promise<void>;
};

function emitStorageError(operation: string, cause: unknown) {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new CustomEvent(STORAGE_ERROR_EVENT, { detail: { operation, cause } }));
}

function requestResult<T>(request: IDBRequest<T>) {
  return new Promise<T>((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("indexeddb_request_failed"));
  });
}

function transactionDone(transaction: IDBTransaction) {
  return new Promise<void>((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onabort = () => reject(transaction.error ?? new Error("indexeddb_transaction_aborted"));
    transaction.onerror = () => reject(transaction.error ?? new Error("indexeddb_transaction_failed"));
  });
}

async function openDatabase() {
  if (typeof indexedDB === "undefined") throw new Error("indexeddb_unavailable");
  const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
  request.onupgradeneeded = () => {
    if (!request.result.objectStoreNames.contains(STORE_NAME)) request.result.createObjectStore(STORE_NAME);
  };
  return requestResult(request);
}

async function withRetry<T>(operation: () => Promise<T>, attempts = 2): Promise<T> {
  let lastError: unknown;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      return await operation();
    } catch (cause) {
      lastError = cause;
      if (attempt + 1 < attempts) await new Promise((resolve) => setTimeout(resolve, 25));
    }
  }
  throw lastError;
}

const browserStorage: PdaDurableStorage = {
  async read<T>(key: string) {
    return withRetry(async () => {
      const database = await openDatabase();
      try {
        const transaction = database.transaction(STORE_NAME, "readonly");
        return await requestResult(transaction.objectStore(STORE_NAME).get(key)) as T | undefined;
      } finally {
        database.close();
      }
    });
  },
  async write<T>(key: string, value: T) {
    await withRetry(async () => {
      const database = await openDatabase();
      try {
        const transaction = database.transaction(STORE_NAME, "readwrite");
        transaction.objectStore(STORE_NAME).put(value, key);
        await transactionDone(transaction);
      } finally {
        database.close();
      }
    });
  },
  async remove(key: string) {
    await withRetry(async () => {
      const database = await openDatabase();
      try {
        const transaction = database.transaction(STORE_NAME, "readwrite");
        transaction.objectStore(STORE_NAME).delete(key);
        await transactionDone(transaction);
      } finally {
        database.close();
      }
    });
  },
  async clear() {
    await withRetry(async () => {
      const database = await openDatabase();
      try {
        const transaction = database.transaction(STORE_NAME, "readwrite");
        transaction.objectStore(STORE_NAME).clear();
        await transactionDone(transaction);
      } finally {
        database.close();
      }
    });
  }
};

let storage: PdaDurableStorage = browserStorage;
let pendingWrite: Promise<void> = Promise.resolve();

export function setPdaDurableStorageForTests(value?: PdaDurableStorage) {
  storage = value ?? browserStorage;
  pendingWrite = Promise.resolve();
}

export async function readPdaDurableValue<T>(key: string): Promise<T | undefined> {
  try {
    await pendingWrite;
    return await storage.read<T>(key);
  } catch (cause) {
    emitStorageError("read", cause);
    return undefined;
  }
}

export function queuePdaDurableWrite<T>(key: string, value: T) {
  pendingWrite = pendingWrite
    .catch(() => undefined)
    .then(() => storage.write(key, value))
    .catch((cause) => emitStorageError("write", cause));
  return pendingWrite;
}

export function queuePdaDurableRemove(key: string) {
  pendingWrite = pendingWrite
    .catch(() => undefined)
    .then(() => storage.remove(key))
    .catch((cause) => emitStorageError("remove", cause));
  return pendingWrite;
}

export function clearPdaDurableStorage() {
  pendingWrite = pendingWrite
    .catch(() => undefined)
    .then(() => storage.clear())
    .catch((cause) => emitStorageError("clear", cause));
  return pendingWrite;
}

export function flushPdaDurableWrites() {
  return pendingWrite;
}
