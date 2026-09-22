import { useWorkspaceLabels } from "../i18n/workspace";
import { RetryError } from "./ui";

export function LoadState({
  loading,
  error,
  reload,
}: {
  loading: boolean;
  error: string | null;
  reload: () => void;
}) {
  const l = useWorkspaceLabels();
  return (
    <>
      {loading && <p role="status">{l("loading")}</p>}
      {error && <RetryError message={error} onRetry={reload} />}
    </>
  );
}
export function PageButtons({
  page,
  totalPages,
  onPage,
}: {
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}) {
  const l = useWorkspaceLabels();
  return (
    <div className="pagination-controls">
      <button
        type="button"
        disabled={page === 0}
        onClick={() => onPage(page - 1)}
      >
        {l("previous")}
      </button>
      <span>
        {page + 1} / {Math.max(1, totalPages)}
      </span>
      <button
        type="button"
        disabled={page + 1 >= totalPages}
        onClick={() => onPage(page + 1)}
      >
        {l("next")}
      </button>
    </div>
  );
}
