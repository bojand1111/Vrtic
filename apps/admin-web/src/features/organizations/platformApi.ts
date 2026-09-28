import { apiFetch } from '../../api/client';
import { ApiProblem } from '../../api/problem';
import type { EffectiveFlags, Plan, Subscription } from '../billing/billingTypes';

export type OrganizationStatus = 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED';

/** docs/openapi.yaml `Organization`. */
export interface Organization {
  readonly id: string;
  readonly slug: string;
  readonly name: string;
  readonly legalName?: string | null;
  readonly countryCode: string;
  readonly timezone: string;
  readonly defaultLocale: string;
  readonly status: OrganizationStatus;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface OrganizationPage {
  readonly items: readonly Organization[];
  readonly nextCursor: string | null;
}

export interface FeatureFlag {
  readonly key: string;
  readonly description: string;
  readonly defaultEnabled: boolean;
  readonly killSwitch: boolean;
  readonly updatedAt: string;
}

export interface OrganizationCreateInput {
  readonly slug: string;
  readonly name: string;
  readonly legalName?: string;
  readonly countryCode: string;
  readonly timezone: string;
  readonly defaultLocale: string;
  readonly ownerEmail: string;
  readonly planId: string;
}

export interface SubscriptionChange {
  readonly planId?: string;
  readonly status?: 'ACTIVE' | 'PAST_DUE' | 'CANCELLED';
  readonly cancelAtPeriodEnd?: boolean;
  readonly reason: string;
}

export interface SubscriptionCreateInput {
  readonly planId: string;
  readonly status: 'TRIAL' | 'ACTIVE';
  readonly trialEndsAt?: string;
  readonly currentPeriodStart: string;
  readonly currentPeriodEnd: string;
}

const P = '/api/v1/platform';
const org = (id: string) => `${P}/organizations/${encodeURIComponent(id)}`;

export function listOrganizations(search: string, status: string, signal?: AbortSignal): Promise<OrganizationPage> {
  const params = new URLSearchParams({ limit: '100', sort: 'name:asc' });
  if (search.trim().length >= 2) {
    params.set('search', search.trim());
  }
  if (status.length > 0) {
    params.set('status', status);
  }
  return apiFetch<OrganizationPage>(`${P}/organizations?${params.toString()}`, { signal });
}

export const getOrganization = (id: string, signal?: AbortSignal) => apiFetch<Organization>(org(id), { signal });
/** Idempotency-Key is required by the contract (the backend accepts it; enforcement is a later task). */
export const createOrganization = (input: OrganizationCreateInput) =>
  apiFetch<Organization>(`${P}/organizations`, { method: 'POST', body: input, headers: { 'Idempotency-Key': crypto.randomUUID() } });
export const setOrganizationActive = (id: string, active: boolean, reason: string) =>
  apiFetch<Organization>(`${org(id)}/${active ? 'reactivate' : 'deactivate'}`, { method: 'POST', body: { reason }, skipUnauthorizedHandler: true });
export const listPlans = (signal?: AbortSignal) => apiFetch<{ readonly items: readonly Plan[] }>(`${P}/plans?activeOnly=true&limit=100`, { signal });
export const getSubscription = (id: string, signal?: AbortSignal) => apiFetch<Subscription>(`${org(id)}/subscription`, { signal });
export const createSubscription = (id: string, input: SubscriptionCreateInput) =>
  apiFetch<Subscription>(`${org(id)}/subscription`, { method: 'POST', body: input, headers: { 'Idempotency-Key': crypto.randomUUID() } });
export const updateSubscription = (id: string, change: SubscriptionChange) =>
  apiFetch<Subscription>(`${org(id)}/subscription`, { method: 'PATCH', body: change });
export const listFlags = (signal?: AbortSignal) => apiFetch<{ readonly items: readonly FeatureFlag[] }>(`${P}/feature-flags`, { signal });
export const getOrganizationFlags = (id: string, signal?: AbortSignal) => apiFetch<EffectiveFlags>(`${org(id)}/feature-flags`, { signal });
export const setOverride = (id: string, key: string, enabled: boolean, reason: string) =>
  apiFetch<unknown>(`${org(id)}/feature-overrides/${encodeURIComponent(key)}`, { method: 'PUT', body: { enabled, reason } });
export const removeOverride = (id: string, key: string) =>
  apiFetch<undefined>(`${org(id)}/feature-overrides/${encodeURIComponent(key)}`, { method: 'DELETE' });

export function isMfaProblem(error: unknown): boolean {
  return error instanceof ApiProblem && error.status === 403 && (error.detail === 'MFA_REQUIRED' || error.detail === 'MFA_ENROLLMENT_REQUIRED');
}

export function isNotFound(error: unknown): boolean {
  return error instanceof ApiProblem && error.status === 404;
}

export function orgStatusTone(status: OrganizationStatus): 'success' | 'warning' | 'neutral' {
  return status === 'ACTIVE' ? 'success' : status === 'SUSPENDED' ? 'warning' : 'neutral';
}
