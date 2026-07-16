import { useCallback, useEffect, useRef, useState } from "react";
import type { AppKind } from "../types";
import {
  loadTablePreference,
  moveTableColumnByKeyboard,
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

  useEffect(() => {
    let active = true;
    const currentDefinitions = definitionsRef.current;
    const localState: LayoutState<Key> = {
      identity,
      layout: readStoredTableLayout(app, username, tableKey, currentDefinitions, storage),
      revision: 0
    };

    stateRef.current = localState;
    setState(localState);
    lastSavedRevisionRef.current = 0;

    if (!accessToken) {
      setReady(true);
      return () => {
        active = false;
      };
    }

    setReady(false);
    void loadTablePreference<Key>(app, tableKey, accessToken)
      .then((preference) => {
        if (
          !active
          || preference.app !== app
          || preference.tableKey !== tableKey
          || stateRef.current.identity !== identity
          || stateRef.current.revision !== 0
        ) {
          return;
        }
        const backendState: LayoutState<Key> = {
          identity,
          layout: sanitizeSavedTableLayout(preference.columns, currentDefinitions),
          revision: 0
        };
        stateRef.current = backendState;
        setState(backendState);
        writeStoredTableLayout(app, username, tableKey, backendState.layout, storage);
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
    };
  }, [accessToken, app, identity, signature, storage, tableKey, username]);

  useEffect(() => {
    if (state.identity === identity && state.revision > 0) {
      writeStoredTableLayout(app, username, tableKey, state.layout, storage);
    }
  }, [app, identity, state, storage, tableKey, username]);

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
      void saveTablePreference(app, tableKey, columns, accessToken)
        .then(() => {
          if (stateRef.current.identity === identity) {
            lastSavedRevisionRef.current = Math.max(lastSavedRevisionRef.current, revision);
          }
        })
        .catch(() => {
          // The latest layout is still durable in local storage and can be retried after another change.
        });
    }, saveDelay(debounceMs));

    return () => globalThis.clearTimeout(timeout);
  }, [accessToken, app, debounceMs, identity, ready, state, tableKey]);

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
    setState(nextState);
  }, [identity]);

  const reorderColumns = useCallback((draggedKey: Key, targetKey: Key) => {
    updateLayout((layout) => reorderTableColumns(layout, draggedKey, targetKey));
  }, [updateLayout]);

  const moveColumn = useCallback((columnKey: Key, direction: TableColumnMoveDirection) => {
    updateLayout((layout) => moveTableColumnByKeyboard(layout, columnKey, direction));
  }, [updateLayout]);

  const resizeColumn = useCallback((columnKey: Key, width: number) => {
    updateLayout((layout) => resizeTableColumn(layout, columnKey, width));
  }, [updateLayout]);

  const toggleColumnVisibility = useCallback((columnKey: Key) => {
    updateLayout((layout) => toggleTableColumnVisibility(layout, columnKey));
  }, [updateLayout]);

  return {
    layout: state.identity === identity
      ? state.layout
      : readStoredTableLayout(app, username, tableKey, definitions, storage),
    ready: ready && state.identity === identity,
    reorderColumns,
    moveColumn,
    resizeColumn,
    toggleColumnVisibility
  };
}
