


import type { AuditLog } from "../../lib/types";
import { useI18n } from "../../i18n/index";
import { SectionHeader, EmptyState } from "../../shared/ui";
import { auditActionLabel, formatDate } from "../../shared/lib";

export function AuditView({ audit }: { audit: AuditLog[] }) {
  const { t } = useI18n();
  return (
    <section className="content-section">
      <SectionHeader title={t("adminAudit")} subtitle={`${audit.length} ${t("recentActions")}`} />
      <AuditList audit={audit} expanded />
    </section>
  );
}

export function AuditList({ audit, expanded = false }: { audit: AuditLog[]; expanded?: boolean }) {
  const { t } = useI18n();
  if (audit.length === 0) return <EmptyState text={t("noAuditActions")} />;
  return (
    <div className="audit-list">
      {audit.map((item) => (
        <article className="audit-row" key={item.id}>
          <div>
            <strong>{auditActionLabel(item.action)}</strong>
            <span>{item.username} · {formatDate(item.createdAt)}</span>
          </div>
          {expanded && (
            <div className="audit-detail">
              <code>{item.targetType}:{item.targetId}</code>
              {item.details && <p>{item.details}</p>}
            </div>
          )}
        </article>
      ))}
    </div>
  );
}
