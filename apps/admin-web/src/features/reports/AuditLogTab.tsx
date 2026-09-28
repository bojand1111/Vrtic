import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrgQuery } from '../../api/org';
import { useFormat } from '../../app/format';
import { EmptyState } from '../../components/EmptyState';
import { InputField, SelectField } from '../../components/Form';
import { Badge, Loading } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { type AuditFilters, auditQuery, formatMetadata } from './reportHelpers';
import type { AuditLogPage } from './reportTypes';

const PAGE_SIZE = 50;
const EMPTY: AuditFilters = { from: '', to: '', action: '', entityType: '', result: '' };

/** GET /audit-log (AUDIT_READ): filters by date range, action, entity type and result; cursor paging. */
export function AuditLogTab() {
  const { t } = useTranslation();
  const format = useFormat();
  const [filters, setFilters] = useState<AuditFilters>(EMPTY);
  // cursors of the pages already visited; the last one is the current page (null = first page)
  const [cursors, setCursors] = useState<readonly (string | null)[]>([null]);
  const cursor = cursors.at(-1) ?? null;
  const page = useOrgQuery<AuditLogPage>(['audit-log'], auditQuery(filters, PAGE_SIZE, cursor));
  const patch = (change: Partial<AuditFilters>) => {
    setFilters((prev) => ({ ...prev, ...change }));
    setCursors([null]);
  };
  const next = page.data?.nextCursor ?? null;

  return (
    <>
      <div className="vc-toolbar">
        <InputField id="audit-from" type="date" label={t('ui.from')} value={filters.from} onChange={(v) => { patch({ from: v }); }} />
        <InputField id="audit-to" type="date" label={t('ui.to')} value={filters.to} onChange={(v) => { patch({ to: v }); }} />
        <InputField id="audit-action" label={t('reports.audit.action')} value={filters.action} placeholder="CHILD_UPDATED" onChange={(v) => { patch({ action: v }); }} />
        <InputField id="audit-entity" label={t('reports.audit.entityType')} value={filters.entityType} placeholder="CHILD" onChange={(v) => { patch({ entityType: v }); }} />
        <SelectField
          id="audit-result"
          label={t('reports.audit.result')}
          value={filters.result}
          onChange={(v) => { patch({ result: v }); }}
          emptyLabel={t('ui.all')}
          options={(['SUCCESS', 'DENIED', 'FAILED'] as const).map((r) => ({ value: r, label: t(`reports.audit.results.${r}`) }))}
        />
      </div>
      <ProblemAlert error={page.error} />
      {page.isPending ? <Loading /> : null}
      {page.data?.items.length === 0 ? <EmptyState message={t('reports.audit.empty')} /> : null}
      {page.data === undefined || page.data.items.length === 0 ? null : (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th>{t('reports.audit.when')}</th>
                <th>{t('reports.audit.actor')}</th>
                <th>{t('reports.audit.action')}</th>
                <th>{t('reports.audit.entityType')}</th>
                <th>{t('reports.audit.entityId')}</th>
                <th>{t('reports.audit.result')}</th>
                <th>{t('reports.audit.details')}</th>
              </tr>
            </thead>
            <tbody>
              {page.data.items.map((e) => (
                <tr key={e.id}>
                  <td>{format.dateTime(e.occurredAt)}</td>
                  <td>{e.actorName ?? (e.actorUserId == null ? t('reports.audit.system') : e.actorUserId.slice(0, 8))}</td>
                  <td>
                    <code>{e.action}</code>
                  </td>
                  <td>{e.entityType}</td>
                  <td>{e.entityId == null ? '' : <code title={e.entityId}>{e.entityId.slice(0, 8)}</code>}</td>
                  <td>
                    <Badge tone={e.result === 'SUCCESS' ? 'success' : e.result === 'DENIED' ? 'warning' : 'danger'}>{t(`reports.audit.results.${e.result}`)}</Badge>
                  </td>
                  <td>
                    {e.purpose == null ? null : <span className="vc-muted">{e.purpose} </span>}
                    {formatMetadata(e.metadata)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="vc-toolbar">
        <button type="button" className="vc-button vc-button--small" disabled={cursors.length <= 1} onClick={() => { setCursors((prev) => prev.slice(0, -1)); }}>
          {t('reports.audit.previous')}
        </button>
        <button type="button" className="vc-button vc-button--small" disabled={next === null} onClick={() => { if (next !== null) setCursors((prev) => [...prev, next]); }}>
          {t('reports.audit.next')}
        </button>
      </div>
    </>
  );
}
