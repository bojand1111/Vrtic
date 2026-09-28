import { type SubmitEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useOrg, useOrgMutation, useOrgQuery } from '../../api/org';
import { fieldError } from '../../api/problem';
import { Alert } from '../../components/Alert';
import { CheckboxField, FieldRow, InputField } from '../../components/Form';
import { Loading, Page } from '../../components/Page';
import { ProblemAlert } from '../../components/ProblemAlert';

/** docs/openapi.yaml `OrganizationSettings`. */
export interface OrganizationSettings {
  readonly scheduleChangeDeadlineHours: number;
  readonly lateArrivalGraceMinutes: number;
  readonly dayOpensAt: string;
  readonly dayClosesAt: string;
  /** ISO weekdays, 1 = Monday ... 7 = Sunday. */
  readonly workingWeekdays: readonly number[];
  readonly offlineCacheTtlHours: number;
  readonly updatedAt: string;
}

const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7] as const;

/** Every member may read the settings; only ORG_SETTINGS_MANAGE (OWNER) may change them. */
export function SettingsPage() {
  const { t } = useTranslation();
  const { can } = useOrg();
  const query = useOrgQuery<OrganizationSettings>(['settings'], '/settings');
  const canEdit = can('ORG_SETTINGS_MANAGE');
  // Kept here: a successful save refetches the settings and remounts the form (new updatedAt).
  const [saved, setSaved] = useState(false);

  return (
    <Page title={t('nav.settings')}>
      <ProblemAlert error={query.error} />
      {query.isPending ? <Loading /> : null}
      {canEdit ? null : <Alert variant="info" title={t('settings.readOnly')} />}
      {saved ? <Alert variant="info" title={t('ui.saved')} /> : null}
      {query.data === undefined ? null : (
        <SettingsForm key={query.data.updatedAt} initial={query.data} canEdit={canEdit} onSaved={setSaved} />
      )}
    </Page>
  );
}

interface SettingsFormProps {
  readonly initial: OrganizationSettings;
  readonly canEdit: boolean;
  readonly onSaved: (saved: boolean) => void;
}

function SettingsForm({ initial, canEdit, onSaved }: SettingsFormProps) {
  const { t } = useTranslation();
  const mutation = useOrgMutation<OrganizationSettings>(['settings']);
  const [deadline, setDeadline] = useState(String(initial.scheduleChangeDeadlineHours));
  const [grace, setGrace] = useState(String(initial.lateArrivalGraceMinutes));
  const [opens, setOpens] = useState(initial.dayOpensAt);
  const [closes, setCloses] = useState(initial.dayClosesAt);
  const [weekdays, setWeekdays] = useState<readonly number[]>(initial.workingWeekdays);
  const [ttl, setTtl] = useState(String(initial.offlineCacheTtlHours));

  function toggleDay(day: number, checked: boolean) {
    setWeekdays((current) => (checked ? [...current.filter((d) => d !== day), day].sort((a, b) => a - b) : current.filter((d) => d !== day)));
  }

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    onSaved(false);
    mutation.mutate(
      {
        method: 'PUT',
        path: '/settings',
        body: {
          scheduleChangeDeadlineHours: Number(deadline),
          lateArrivalGraceMinutes: Number(grace),
          dayOpensAt: opens,
          dayClosesAt: closes,
          workingWeekdays: weekdays,
          offlineCacheTtlHours: Number(ttl),
        },
      },
      {
        onSuccess: () => {
          onSaved(true);
        },
      },
    );
  }

  const disabled = !canEdit || mutation.isPending;

  return (
    <form className="vc-section" onSubmit={submit} noValidate>
      <ProblemAlert error={mutation.error} />
      <FieldRow>
        <InputField
          id="settings-opens"
          type="time"
          label={t('settings.dayOpensAt')}
          value={opens}
          onChange={setOpens}
          disabled={disabled}
          required
          error={fieldError(mutation.error, 'dayOpensAt')}
        />
        <InputField
          id="settings-closes"
          type="time"
          label={t('settings.dayClosesAt')}
          value={closes}
          onChange={setCloses}
          disabled={disabled}
          required
          error={fieldError(mutation.error, 'dayClosesAt')}
        />
      </FieldRow>
      <fieldset className="vc-field" disabled={disabled}>
        <legend>{t('settings.workingWeekdays')}</legend>
        {WEEKDAYS.map((day) => (
          <CheckboxField
            key={day}
            id={`settings-weekday-${String(day)}`}
            label={t(`settings.weekdays.${day}`)}
            checked={weekdays.includes(day)}
            onChange={(checked) => {
              toggleDay(day, checked);
            }}
          />
        ))}
        {fieldError(mutation.error, 'workingWeekdays') === undefined ? null : (
          <p className="vc-field-error">{fieldError(mutation.error, 'workingWeekdays')}</p>
        )}
      </fieldset>
      <FieldRow>
        <InputField
          id="settings-deadline"
          type="number"
          min={0}
          max={168}
          label={t('settings.scheduleChangeDeadlineHours')}
          hint={t('settings.scheduleChangeDeadlineHint')}
          value={deadline}
          onChange={setDeadline}
          disabled={disabled}
          error={fieldError(mutation.error, 'scheduleChangeDeadlineHours')}
        />
        <InputField
          id="settings-grace"
          type="number"
          min={0}
          max={180}
          label={t('settings.lateArrivalGraceMinutes')}
          hint={t('settings.lateArrivalGraceHint')}
          value={grace}
          onChange={setGrace}
          disabled={disabled}
          error={fieldError(mutation.error, 'lateArrivalGraceMinutes')}
        />
        <InputField
          id="settings-ttl"
          type="number"
          min={1}
          max={48}
          label={t('settings.offlineCacheTtlHours')}
          hint={t('settings.offlineCacheTtlHint')}
          value={ttl}
          onChange={setTtl}
          disabled={disabled}
          error={fieldError(mutation.error, 'offlineCacheTtlHours')}
        />
      </FieldRow>
      {canEdit ? (
        <button type="submit" className="vc-button vc-button--primary" disabled={mutation.isPending}>
          {mutation.isPending ? t('ui.saving') : t('ui.save')}
        </button>
      ) : null}
    </form>
  );
}
