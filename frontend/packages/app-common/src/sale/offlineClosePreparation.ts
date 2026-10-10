type Preparation = () => Promise<void>;
const preparations = new Set<Preparation>();
let prepared = false;
let flight: Promise<void> | null = null;

export function registerOfflineClosePreparation(handler: Preparation): () => void {
  prepared = false;
  preparations.add(handler);
  return () => { preparations.delete(handler); };
}

export function isOfflineApplicationClosePrepared() { return prepared; }
export function resetOfflineApplicationClosePreparation() { prepared = false; }

/** No backend mutations belong here. Failure must keep the application open. */
export function prepareOfflineApplicationClose(): Promise<void> {
  if (flight) return flight;
  prepared = false;
  flight = (async () => {
    for (const prepare of preparations) await prepare();
    prepared = true;
  })().finally(() => { flight = null; });
  return flight;
}
