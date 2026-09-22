import type { ReactNode } from "react";
import { WorkspaceDialog } from "../../shared/WorkspaceDialog";
import { useLicenseLabels } from "./license-labels";
import "./license-workspace.css";

export function LicenseDialog({ reference, busy, onClose, restoreFocus, children }: {
  reference: string; busy: boolean; onClose: () => void; restoreFocus: HTMLElement | (() => HTMLElement | null) | null; children: ReactNode;
}) {
  const l = useLicenseLabels();
  return <WorkspaceDialog className="saas-license-dialog" title={reference} subtitle={l("configuration")}
    closeLabel={l("close")} busy={busy} onClose={onClose} restoreFocus={restoreFocus}>{children}</WorkspaceDialog>;
}
