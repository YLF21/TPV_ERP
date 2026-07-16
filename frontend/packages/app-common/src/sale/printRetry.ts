export async function retryPrintSucceeded(retry: () => Promise<unknown>): Promise<boolean> {
  try { return (await retry() as { status?: string })?.status === "PRINTED"; }
  catch { return false; }
}
