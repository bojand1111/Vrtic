import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useFormat } from '../../app/format';
import { FieldRow, InputField, TextAreaField } from '../../components/Form';
import { Modal } from '../../components/Modal';
import { ProblemAlert } from '../../components/ProblemAlert';
import { useVersionedMutation } from '../announcements/versionedMutation';
import { MEAL_SLOTS, type MealSlot, type MenuDay } from './menuTypes';
import { parseAllergenTags } from './week';

interface SlotDraft {
  readonly description: string;
  readonly allergens: string;
}

interface MenuDayFormProps {
  readonly date: string;
  /** Existing organization-wide menu of the date, or null to create one. */
  readonly day: MenuDay | null;
  readonly onClose: () => void;
}

/** MENU_MANAGE: note and one dish per meal slot; items are replaced as a set. */
export function MenuDayForm({ date, day, onClose }: MenuDayFormProps) {
  const { t } = useTranslation();
  const fmt = useFormat();
  const mutation = useVersionedMutation<MenuDay>(['meals']);
  const [note, setNote] = useState(day?.note ?? '');
  const [slots, setSlots] = useState<Readonly<Record<MealSlot, SlotDraft>>>(() => {
    const initial = {} as Record<MealSlot, SlotDraft>;
    for (const slot of MEAL_SLOTS) {
      const items = (day?.items ?? []).filter((i) => i.mealSlot === slot);
      initial[slot] = {
        description: items.map((i) => i.description).join('; '),
        allergens: [...new Set(items.flatMap((i) => i.allergenTags))].join(', '),
      };
    }
    return initial;
  });

  const update = (slot: MealSlot, patch: Partial<SlotDraft>) => {
    setSlots((prev) => ({ ...prev, [slot]: { ...prev[slot], ...patch } }));
  };

  const submit = () => {
    const items = MEAL_SLOTS.filter((slot) => slots[slot].description.trim() !== '').map((slot) => ({
      mealSlot: slot,
      description: slots[slot].description.trim(),
      allergenTags: parseAllergenTags(slots[slot].allergens),
      sortOrder: 0,
    }));
    const noteValue = note.trim() === '' ? null : note.trim();
    mutation.mutate(
      day === null
        ? { method: 'POST', path: '/menu-days', body: { menuDate: date, items, ...(noteValue === null ? {} : { note: noteValue }) } }
        : { method: 'PUT', path: `/menu-days/${day.id}`, body: { note: noteValue, items }, version: day.version },
      { onSuccess: onClose },
    );
  };

  return (
    <Modal
      title={`${t('meals.editDay')}: ${fmt.date(date)}`}
      open
      onClose={onClose}
      footer={
        <>
          <button type="button" className="vc-button" onClick={onClose}>
            {t('ui.cancel')}
          </button>
          <button type="button" className="vc-button vc-button--primary" disabled={mutation.isPending} onClick={submit}>
            {mutation.isPending ? t('ui.saving') : t('ui.save')}
          </button>
        </>
      }
    >
      {MEAL_SLOTS.map((slot) => (
        <FieldRow key={slot}>
          <InputField
            id={`menu-${slot}-description`}
            label={t(`meals.slots.${slot}`)}
            value={slots[slot].description}
            onChange={(v) => { update(slot, { description: v }); }}
          />
          <InputField
            id={`menu-${slot}-allergens`}
            label={t('meals.allergens')}
            value={slots[slot].allergens}
            placeholder={t('meals.allergensPlaceholder')}
            onChange={(v) => { update(slot, { allergens: v }); }}
          />
        </FieldRow>
      ))}
      <p className="vc-muted">{t('meals.allergensHint')}</p>
      <TextAreaField id="menu-note" label={t('ui.note')} value={note} onChange={setNote} rows={2} />
      <ProblemAlert error={mutation.error} />
    </Modal>
  );
}
