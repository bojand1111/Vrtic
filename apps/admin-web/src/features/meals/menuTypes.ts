/** docs/openapi.yaml MenuDay / MenuItem / MealSlot. */
export const MEAL_SLOTS = ['BREAKFAST', 'SNACK_AM', 'LUNCH', 'SNACK_PM'] as const;
export type MealSlot = (typeof MEAL_SLOTS)[number];

export interface MenuItem {
  readonly id: string;
  readonly mealSlot: MealSlot;
  readonly description: string;
  readonly allergenTags: readonly string[];
  readonly sortOrder: number;
}

export interface MenuDay {
  readonly id: string;
  readonly locationId?: string | null;
  readonly menuDate: string;
  readonly isPublished: boolean;
  readonly note?: string | null;
  readonly items: readonly MenuItem[];
  readonly version: number;
}
