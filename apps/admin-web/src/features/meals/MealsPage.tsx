import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgQuery } from '../../api/org';
import { todayIso } from '../../app/format';
import { Badge, Loading, Page } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';
import { type Page as ListPage, useVersionedMutation } from '../announcements/versionedMutation';
import { MenuDayForm } from './MenuDayForm';
import { MEAL_SLOTS, type MenuDay } from './menuTypes';
import { addDays, mondayOf, workWeek } from './week';
import './meals.css';

export function MealsPage() {
  const { t, i18n } = useTranslation();
  const { can } = useOrg();
  const [monday, setMonday] = useState(mondayOf(todayIso()));
  const days = workWeek(monday);
  const friday = days[days.length - 1] ?? monday;
  const canManage = can('MENU_MANAGE');
  const menus = useOrgQuery<ListPage<MenuDay>>(['meals'], `/menu-days?from=${monday}&to=${friday}&limit=100`, { enabled: can('MENU_READ') });
  const publish = useVersionedMutation<MenuDay>(['meals']);
  const [editing, setEditing] = useState<string | null>(null);

  const locale = i18n.language === 'en' ? 'en-GB' : i18n.language === 'sr-Cyrl' ? 'sr-Cyrl-RS' : 'sr-Latn-RS';
  const dayLabel = (iso: string) => new Date(`${iso}T00:00:00`).toLocaleDateString(locale, { weekday: 'long', day: 'numeric', month: 'numeric' });
  // The organization-wide menu of a date (location-specific menus are listed after it).
  const menusOf = (iso: string) =>
    (menus.data?.items ?? []).filter((m) => m.menuDate === iso).sort((a, b) => (a.locationId == null ? -1 : b.locationId == null ? 1 : 0));
  const orgMenu = (iso: string) => menusOf(iso).find((m) => m.locationId == null) ?? null;

  return (
    <Page title={t('nav.meals')}>
      {!can('MENU_READ') ? (
        <p>{t('ui.noAccess')}</p>
      ) : (
        <>
          <div className="vc-toolbar">
            <button type="button" className="vc-button" aria-label={t('meals.previousWeek')} onClick={() => { setMonday(addDays(monday, -7)); }}>
              ‹
            </button>
            <strong>
              {new Date(`${monday}T00:00:00`).toLocaleDateString(locale)} – {new Date(`${friday}T00:00:00`).toLocaleDateString(locale)}
            </strong>
            <button type="button" className="vc-button" aria-label={t('meals.nextWeek')} onClick={() => { setMonday(addDays(monday, 7)); }}>
              ›
            </button>
            <button type="button" className="vc-button vc-button--ghost" onClick={() => { setMonday(mondayOf(todayIso())); }}>
              {t('meals.thisWeek')}
            </button>
          </div>
          <ProblemAlert error={publish.error} />
          {menus.isPending ? <Loading /> : null}
          {menus.isError ? <ProblemAlert error={menus.error} /> : null}
          {menus.data === undefined ? null : (
            <div className="vc-meals-week">
              {days.map((iso) => {
                const list = menusOf(iso);
                const own = orgMenu(iso);
                return (
                  <section key={iso} className="vc-card vc-meals-day">
                    <h3>{dayLabel(iso)}</h3>
                    {list.length === 0 ? <p className="vc-muted">{t('meals.noMenu')}</p> : null}
                    {list.map((m) => (
                      <div key={m.id} className="vc-meals-menu">
                        {canManage ? (
                          <Badge tone={m.isPublished ? 'success' : 'neutral'}>{m.isPublished ? t('meals.published') : t('meals.draft')}</Badge>
                        ) : null}
                        {m.locationId == null ? null : <Badge tone="info">{t('meals.locationMenu')}</Badge>}
                        <dl className="vc-meals-slots">
                          {MEAL_SLOTS.map((slot) => {
                            const items = m.items.filter((i) => i.mealSlot === slot);
                            return (
                              <div key={slot}>
                                <dt>{t(`meals.slots.${slot}`)}</dt>
                                <dd>
                                  {items.length === 0 ? <span className="vc-muted">{t('meals.empty')}</span> : null}
                                  {items.map((i) => (
                                    <span key={i.id} className="vc-meals-item">
                                      {i.description}
                                      {i.allergenTags.map((tag) => (
                                        <Badge key={tag} tone="warning">
                                          {tag}
                                        </Badge>
                                      ))}
                                    </span>
                                  ))}
                                </dd>
                              </div>
                            );
                          })}
                        </dl>
                        {m.note == null || m.note === '' ? null : <p className="vc-pre">{m.note}</p>}
                      </div>
                    ))}
                    {canManage ? (
                      <div className="vc-meals-actions">
                        <button type="button" className="vc-button vc-button--small" onClick={() => { setEditing(iso); }}>
                          {own === null ? t('meals.add') : t('ui.edit')}
                        </button>
                        {own !== null && !own.isPublished ? (
                          <button
                            type="button"
                            className="vc-button vc-button--small vc-button--primary"
                            disabled={publish.isPending}
                            onClick={() => { publish.mutate({ method: 'POST', path: `/menu-days/${own.id}/publish`, version: own.version }); }}
                          >
                            {t('meals.publish')}
                          </button>
                        ) : null}
                      </div>
                    ) : null}
                  </section>
                );
              })}
            </div>
          )}
          <p className="vc-muted">{t('meals.disclaimer')}</p>
        </>
      )}
      {editing === null ? null : <MenuDayForm date={editing} day={orgMenu(editing)} onClose={() => { setEditing(null); }} />}
    </Page>
  );
}
