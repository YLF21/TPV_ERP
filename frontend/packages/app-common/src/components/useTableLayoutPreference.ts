import { useCallback, useEffect, useRef, useState } from "react";
import type { AppKind } from "../types";
import {
  loadTablePreference,
  moveTableColumnByKeyboard,
  readStoredTablePreference,
  readStoredTableLayout,
  reorderTableColumns,
  resizeTableColumn,
  sanitizeSavedTableLayout,
  saveTablePreference,
  tableLayoutStorageKey,
  toggleTableColumnVisibility,
  writeStoredTableLayout
} from "./tableLayoutPreferences";
import type {
  TableColumnDefinition,
  TableColumnMoveDirection,
  TableLayout
} from "./tableLayoutPreferences";

export const DEFAULT_TABLE_LAYOUT_SAVE_DEBOUNCE_MS = 300;

export type UseTableLayoutPreferenceOptions<Key extends string = string> = {
  app: AppKind;
  username: string;
  accessToken?: string;
  tableKey: string;
  definitions: readonly TableColumnDefinition<Key>[];
  debounceMs?: number;
  storage?: Storage;
};

export type UseTableLayoutPreferenceResult<Key extends string = string> = {
  layout: TableLayout<Key>;
  ready: boolean;
  replaceLayout: (layout: TableLayout<Key>) => void;
  reorderColumns: (draggedKey: Key, targetKey: Key) => void;
  moveColumn: (columnKey: Key, direction: TableColumnMoveDirection) => void;
  resizeColumn: (columnKey: Key, width: number) => void;
  toggleColumnVisibility: (columnKey: Key) => void;
};

type LayoutState<Key extends string> = {
  identity: string;
  layout: TableLayout<Key>;
  revision: number;
};

const tablePreferenceSaveQueues = new Map<string, Promise<void>>();

function definitionsSignature<Key extends string>(
  definitions: readonly TableColumnDefinition<Key>[]
): string {
  return JSON.stringify(definitions.map((definition) => [
    definition.key,
    definition.defaultWidth,
    definition.defaultVisible !== false
  ]));
}

function saveDelay(debounceMs?: number): number {
  if (debounceMs === undefined || !Number.isFinite(debounceMs)) {
    return DEFAULT_TABLE_LAYOUT_SAVE_DEBOUNCE_MS;
  }
  return Math.max(0, Math.round(debounceMs));
}

export function useTableLayoutPreference<Key extends string = string>({
  app,
  username,
  accessToken,
  tableKey,
  definitions,
  debounceMs,
  storage
}: UseTableLayoutPreferenceOptions<Key>): UseTableLayoutPreferenceResult<Key> {
  const identity = tableLayoutStorageKey(app, username, tableKey);
  const signature = definitionsSignature(definitions);
  const definitionsRef = useRef(definitions);
  definitionsRef.current = definitions;

  const [state, setState] = useState<LayoutState<Key>>(() => ({
    identity,
    layout: readStoredTableLayout(app, username, tableKey, definitions, storage),
    revision: 0
  }));
  const stateRef = useRef(state);
  stateRef.current = state;
  const [ready, setReady] = useState(!accessToken);
  const lastSavedRevisionRef = useRef(0);
  const enqueueSave = useCallback((
    revision: number,
    columns: TableLayout<Key>
  ) => {
    if (!accessToken) {
      return;
    }
    const token = accessToken;
    const queuedSave = (tablePreferenceSaveQueues.get(identity) ?? Promise.resolve())
      .catch(() => undefined)
      .then(() => saveTablePreference(app, tableKey, columns, token))
      .then((savedPreference) => {
        if (stateRef.current.identity === identity) {
          if (
            stateRef.current.revision === revision
            && savedPreference.updatedAt
          ) {
            writeStoredTableLayout(
              app,
              username,
              tableKey,
              columns,
              storage,
              savedPreference.updatedAt
            );
          }
          lastSavedRevisionRef.current = Math.max(lastSavedRevisionRef.current, revision);
        }
      })
      .catch(() => {
        // The latest layout remains durable in local storage and a newer
        // queued revision can still be persisted after this failure.
      });
    tablePreferenceSaveQueues.set(identity, queuedSave);
    void queuedSave.then(() => {
      if (tablePreferenceSaveQueues.get(identity) === queuedSave) {
        tablePreferenceSaveQueues.delete(identity);
      }
    });
  }, [accessToken, app, identity, storage, tableKey, username]);

  const flushPendingSave = useCallback(() => {
    const pending = stateRef.current;
    if (
      pending.identity === identity
      && pending.revision > lastSavedRevisionRef.current
    ) {
      enqueueSave(pending.revision, pending.layout);
    }
  }, [enqueueSave, identity]);

  useEffect(() => {
    let active = true;
    const currentDefinitions = definitionsRef.current;
    const storedPreference = readStoredTablePreference(
      app,
      username,
      tableKey,
      currentDefinitions,
      storage
    );
    const localUpdatedAt = storedPreference.legacy
      ? new Date().toISOString()
      : storedPreference.updatedAt;
    if (storedPreference.legacy && localUpdatedAt) {
      writeStoredTableLayout(
        app,
        username,
        tableKey,
        storedPreference.layout,
        storage,
        localUpdatedAt
      );
    }
    const localState: LayoutState<Key> = {
      identity,
      layout: storedPreference.layout,
      revision: 0
    };

    stateRef.current = localState;
    setState(localState);
    lastSavedRevisionRef.current = 0;

    if (!accessToken) {
      setReady(true);
      return () => {
        active = false;
        flushPendingSave();
      };
    }

    setReady(false);
    const pendingSaves = tablePreferenceSaveQueues.get(identity) ?? Promise.resolve();
    void pendingSaves
      .then(() => {
        if (!active) {
          return undefined;
        }
        return loadTablePreference<Key>(app, tableKey, accessToken);
      })
      .then((preference) => {
        if (
          !preference
          ||
          !active
          || preference.app !== app
          || preference.tableKey !== tableKey
          || stateRef.current.identity !== identity
          || stateRef.current.revision !== 0
        ) {
          return;
        }
        const backendUpdatedAt = preference.updatedAt
          && Number.isFinite(Date.parse(preference.updatedAt))
          ? preference.updatedAt
          : undefined;
        const localIsNewer = storedPreference.exists && (
          storedPreference.legacy
          || !backendUpdatedAt
          || Boolean(localUpdatedAt && Date.parse(localUpdatedAt) > Date.parse(backendUpdatedAt))
        );
        if (localIsNewer) {
          enqueueSave(0, localState.layout);
          return;
        }
        const backendState: LayoutState<Key> = {
          identity,
          layout: sanitizeSavedTableLayout(preference.columns, currentDefinitions),
          revision: 0
        };
        stateRef.current = backendState;
        setState(backendState);
        writeStoredTableLayout(
          app,
          username,
          tableKey,
          backendState.layout,
          storage,
          backendUpdatedAt
        );
      })
      .catch(() => {
        // The synchronously loaded local preference remains authoritative offline.
      })
      .finally(() => {
        if (active) {
          setReady(true);
        }
      });

    return () => {
      active = false;
      flushPendingSave();
    };
  }, [accessToken, app, flushPendingSave, identity, signature, storage, tableKey, username]);

  useEffect(() => {
    if (
      !accessToken
      || !ready
      || state.identity !== identity
      || state.revision === 0
      || state.revision <= lastSavedRevisionRef.current
    ) {
      return;
    }

    const revision = state.revision;
    const columns = state.layout;
    const timeout = globalThis.setTimeout(() => {
      enqueueSave(revision, columns);
    }, saveDelay(debounceMs));

    return () => globalThis.clearTimeout(timeout);
  }, [accessToken, debounceMs, enqueueSave, identity, ready, state]);

  const updateLayout = useCallback((
    update: (layout: TableLayout<Key>) => TableLayout<Key>
  ) => {
    const current = stateRef.current;
    if (current.identity !== identity) {
      return;
    }
    const nextLayout = update(current.layout);
    if (nextLayout === current.layout) {
      return;
    }
    const nextState: LayoutState<Key> = {
      identity,
      layout: nextLayout,
      revision: current.revision + 1
    };
    stateRef.current = nextState;
    writeStoredTableLayout(app, username, tableKey, nextLayout, storage);
    setState(nextState);
  }, [app, identity, storage, tableKey, username]);

  const reorderColumns = useCallback((draggedKey: Key, targetKey: Key) => {
    updateLayout((layout) => reorderTableColumns(layout, draggedKey, targetKey));
  }, [updateLayout]);

  const moveColumn = useCallback((columnKey: Key, direction: TableColumnMoveDirection) => {
    updateLayout((layout) => moveTableColumnByKeyboard(layout, columnKey, direction));
  }, [updateLayout]);

  const resizeColumn = useCallback((columnKey: Key, width: number) => {
    const definition = definitionsRef.current.find((candidate) => candidate.key === columnKey);
    updateLayout((layout) => resizeTableColumn(
      layout,
      columnKey,
      width,
      definition?.minWidth
    ));
  }, [updateLayout]);

  const toggleColumnVisibility = useCallback((columnKey: Key) => {
    updateLayout((layout) => toggleTableColumnVisibility(layout, columnKey));
  }, [updateLayout]);

  const replaceLayout = useCallback((layout: TableLayout<Key>) => {
    updateLayout(() => sanitizeSavedTableLayout(layout, definitionsRef.current));
  }, [updateLayout]);

  return {
    layout: state.identity === identity
      ? state.layout
      : readStoredTableLayout(app, username, tableKey, definitions, storage),
    ready: ready && state.identity === identity,
    replaceLayout,
    reorderColumns,
    moveColumn,
    resizeColumn,
    toggleColumnVisibility
  };
}
