/** docs/openapi.yaml `Plan`. */
export interface Plan {
  readonly id: string;
  readonly code: 'STARTER' | 'STANDARD' | 'PRO';
  readonly version: number;
  readonly name: string;
  readonly currency: string;
  readonly monthlyPriceMinor: number;
  readonly entitlements: Readonly<Record<string, boolean>>;
  readonly limits: Readonly<Record<string, number>>;
  readonly isActive: boolean;
  readonly createdAt: string;
}

export type SubscriptionStatus = 'TRIAL' | 'ACTIVE' | 'PAST_DUE' | 'CANCELLED';

/** docs/openapi.yaml `Subscription`. */
export interface Subscription {
  readonly id: string;
  readonly organizationId: string;
  readonly plan: Plan;
  readonly status: SubscriptionStatus;
  readonly trialEndsAt?: string | null;
  readonly currentPeriodStart: string;
  readonly currentPeriodEnd: string;
  readonly cancelledAt?: string | null;
  readonly cancelAtPeriodEnd: boolean;
  readonly provider: 'MANUAL' | 'STRIPE' | 'LOCAL_INVOICE';
  readonly createdAt: string;
  readonly updatedAt: string;
}

export type FlagSource = 'KILL_SWITCH' | 'TENANT_OVERRIDE' | 'PLAN_ENTITLEMENT' | 'DEFAULT';

/** docs/openapi.yaml `EffectiveFeatureFlags`. */
export interface EffectiveFlags {
  readonly organizationId: string;
  readonly flags: readonly { readonly key: string; readonly enabled: boolean; readonly source: FlagSource }[];
  readonly computedAt: string;
}

const FLAG_NAMES = ['photos_enabled', 'messaging_enabled', 'meals_enabled', 'calendar_enabled', 'payments_enabled'] as const;
const LIMIT_NAMES = ['max_children', 'max_locations', 'max_staff'] as const;

export type KnownFlag = (typeof FLAG_NAMES)[number];
export type KnownLimit = (typeof LIMIT_NAMES)[number];

export function isKnownFlag(key: string): key is KnownFlag {
  return (FLAG_NAMES as readonly string[]).includes(key);
}

export function isKnownLimit(key: string): key is KnownLimit {
  return (LIMIT_NAMES as readonly string[]).includes(key);
}

/** Minor units -> localized currency amount (integers only, never floating point in storage). */
export function formatMoney(minor: number, currency: string, locale: string): string {
  const intlLocale = locale === 'en' ? 'en-GB' : locale === 'sr-Cyrl' ? 'sr-Cyrl-RS' : 'sr-Latn-RS';
  return new Intl.NumberFormat(intlLocale, { style: 'currency', currency, minimumFractionDigits: 2 }).format(minor / 100);
}
