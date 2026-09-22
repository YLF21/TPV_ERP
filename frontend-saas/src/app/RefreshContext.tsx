import { createContext, useContext, useEffect, useMemo, useState } from "react";
import { errorMessage } from "../shared/lib";

export const RefreshContext = createContext(0);
export const useRefreshVersion = () => useContext(RefreshContext);

/** The caller supplies the resource identity; obsolete responses never replace it. */
export function useRemote<T>(
  load: () => Promise<T>,
  dependencies: readonly unknown[],
) {
  const refresh = useRefreshVersion();
  const [version, setVersion] = useState(0);
  const identity = useMemo(() => Symbol(), [...dependencies, refresh, version]);
  const [result, setResult] = useState<{
    identity: symbol;
    data: T | null;
    error: string | null;
  } | null>(null);
  useEffect(() => {
    let current = true;
    void load()
      .then((data) => {
        if (current) setResult({ identity, data, error: null });
      })
      .catch((reason) => {
        if (current)
          setResult({ identity, data: null, error: errorMessage(reason) });
      });
    return () => {
      current = false;
    };
  }, [identity]);
  const current = result?.identity === identity ? result : null;
  return {
    data: current?.data ?? null,
    error: current?.error ?? null,
    loading: current === null,
    reload: () => setVersion((value) => value + 1),
  };
}
