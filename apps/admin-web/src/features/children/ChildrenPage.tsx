import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgQuery } from '../../api/org';
import { useFormat } from '../../app/format';
import { EmptyState } from '../../components/EmptyState';
import { InputField, SelectField } from '../../components/Form';
import { Badge, Loading, Page } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { AddChildModal } from './AddChildModal';
import { Age } from './Age';
import { ChildDetailPanel } from './ChildDetailPanel';
import { groupsFromChildren } from './helpers';
import { type ChildSummary, childName, type GroupOption, type Page as ApiPage } from './types';

import './children.css';

/** /children: managers and teachers get a table with a detail panel, parents their own children. */
export function ChildrenPage() {
  const { t } = useTranslation();
  const org = useOrg();
  const [selected, setSelected] = useState<string | null>(null);

  if (!org.can('CHILD_READ')) {
    return (
      <Page title={t('nav.children')}>
        <p className="vc-muted">{t('ui.noAccess')}</p>
      </Page>
    );
  }
  if (selected !== null) {
    return (
      <Page title={t('nav.children')}>
        <ChildDetailPanel
          childId={selected}
          onBack={() => {
            setSelected(null);
          }}
        />
      </Page>
    );
  }
  return org.role === 'PARENT' ? <ParentChildren onOpen={setSelected} /> : <StaffChildren onOpen={setSelected} />;
}

function StaffChildren({ onOpen }: { readonly onOpen: (id: string) => void }) {
  const { t } = useTranslation();
  const org = useOrg();
  const format = useFormat();
  const [groupId, setGroupId] = useState('');
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<'ACTIVE' | 'INACTIVE'>('ACTIVE');
  const [adding, setAdding] = useState(false);
  const canManage = org.can('CHILD_MANAGE');

  const params = new URLSearchParams({ limit: '100', status });
  if (groupId !== '') {
    params.set('groupId', groupId);
  }
  if (search.trim().length >= 2) {
    params.set('search', search.trim());
  }
  const list = useOrgQuery<ApiPage<ChildSummary>>(['children'], `/children?${params.toString()}`);
  // Managers pick from every group; teachers from the groups of their own children.
  const groups = useOrgQuery<ApiPage<GroupOption>>(['groups'], '/groups?limit=100', { enabled: canManage });
  const allChildren = useOrgQuery<ApiPage<ChildSummary>>(['children'], '/children?limit=100', { enabled: !canManage });
  const groupOptions = useMemo(() => {
    const source = canManage ? (groups.data?.items ?? []) : groupsFromChildren(allChildren.data?.items ?? []);
    return source.map((g) => ({ value: g.id, label: g.name }));
  }, [canManage, groups.data, allChildren.data]);

  return (
    <Page
      title={t('nav.children')}
      actions={
        canManage ? (
          <button
            type="button"
            className="vc-button vc-button--primary"
            onClick={() => {
              setAdding(true);
            }}
          >
            {t('children.add')}
          </button>
        ) : undefined
      }
    >
      <div className="vc-toolbar vc-children-filters">
        <SelectField id="children-group" label={t('children.fields.group')} value={groupId} onChange={setGroupId} options={groupOptions} emptyLabel={t('children.allGroups')} />
        <InputField id="children-search" label={t('ui.search')} value={search} onChange={setSearch} placeholder={t('children.searchHint')} />
        {canManage ? (
          <SelectField
            id="children-status"
            label={t('ui.status')}
            value={status}
            onChange={(v) => {
              setStatus(v === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE');
            }}
            options={[
              { value: 'ACTIVE', label: t('ui.active') },
              { value: 'INACTIVE', label: t('ui.inactive') },
            ]}
          />
        ) : null}
      </div>
      {canManage ? <ProblemAlert error={groups.error} /> : null}
      <ProblemAlert error={list.error} />
      {list.isPending ? <Loading /> : null}
      {list.data?.items.length === 0 ? <EmptyState message={t('children.empty')} /> : null}
      {list.data !== undefined && list.data.items.length > 0 ? (
        <div className="vc-table-wrap">
          <table className="vc-table">
            <thead>
              <tr>
                <th>{t('children.nameColumn')}</th>
                <th>{t('children.fields.dateOfBirth')}</th>
                <th>{t('children.age')}</th>
                <th>{t('children.fields.group')}</th>
                <th>{t('ui.status')}</th>
                <th>{t('ui.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {list.data.items.map((c) => (
                <tr
                  key={c.id}
                  className="vc-row-clickable"
                  onClick={() => {
                    onOpen(c.id);
                  }}
                >
                  <td>{childName(c)}</td>
                  <td>{format.date(c.dateOfBirth)}</td>
                  <td>
                    <Age dateOfBirth={c.dateOfBirth} />
                  </td>
                  <td>{c.currentEnrollment?.groupName ?? <span className="vc-muted">{t('children.noGroup')}</span>}</td>
                  <td>
                    <Badge tone={c.status === 'ACTIVE' ? 'success' : 'neutral'}>{c.status === 'ACTIVE' ? t('ui.active') : t('ui.inactive')}</Badge>
                  </td>
                  <td className="vc-actions">
                    <button
                      type="button"
                      className="vc-button vc-button--small"
                      onClick={(e) => {
                        e.stopPropagation();
                        onOpen(c.id);
                      }}
                    >
                      {t('ui.details')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {canManage ? (
        <AddChildModal
          open={adding}
          groups={groupOptions}
          onClose={() => {
            setAdding(false);
          }}
          onCreated={(id) => {
            setAdding(false);
            onOpen(id);
          }}
        />
      ) : null}
    </Page>
  );
}

function ParentChildren({ onOpen }: { readonly onOpen: (id: string) => void }) {
  const { t } = useTranslation();
  const format = useFormat();
  const list = useOrgQuery<ApiPage<ChildSummary>>(['children'], '/children?limit=100');
  return (
    <Page title={t('children.myChildren')}>
      <ProblemAlert error={list.error} />
      {list.isPending ? <Loading /> : null}
      {list.data?.items.length === 0 ? <EmptyState message={t('children.noLinkedChildren')} /> : null}
      {list.data !== undefined && list.data.items.length > 0 ? (
        <div className="vc-cards">
          {list.data.items.map((c) => (
            <article key={c.id} className="vc-card">
              <h3>{childName(c)}</h3>
              <p>
                {format.date(c.dateOfBirth)} (<Age dateOfBirth={c.dateOfBirth} />)
              </p>
              <p>{c.currentEnrollment?.groupName ?? t('children.noGroup')}</p>
              <button
                type="button"
                className="vc-button vc-button--small"
                onClick={() => {
                  onOpen(c.id);
                }}
              >
                {t('ui.details')}
              </button>
            </article>
          ))}
        </div>
      ) : null}
    </Page>
  );
}
