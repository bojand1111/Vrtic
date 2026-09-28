/** docs/openapi.yaml `Location` (+ `activeGroupsCount`). Null fields are omitted by the API. */
export interface Location {
  readonly id: string;
  readonly name: string;
  readonly addressLine?: string | null;
  readonly city?: string | null;
  readonly postalCode?: string | null;
  readonly countryCode: string;
  readonly timezone?: string | null;
  readonly phone?: string | null;
  readonly status: 'ACTIVE' | 'INACTIVE';
  readonly activeGroupsCount: number;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface Page<T> {
  readonly items: readonly T[];
  readonly nextCursor?: string | null;
}

/** Trimmed text or null (PATCH clears a field with an explicit null). */
export function textOrNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed.length === 0 ? null : trimmed;
}
