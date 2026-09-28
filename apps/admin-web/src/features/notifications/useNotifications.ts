import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiFetch } from '../../api/client';
import { qk } from '../../tenant/queryKeys';
import { useTenant } from '../../tenant/useTenant';
import type { NotificationPage } from './notificationHelpers';

/** The bell polls the inbox once a minute (docs/PRODUCT_SPEC.md 5.11 R8: polling is the reliable channel). */
export const NOTIFICATION_POLL_MS = 60_000;

/** The inbox is per user and spans all organizations, so the cache key has no organization. */
function notificationsKey(userId: string | null) {
  return qk(userId, null, 'notifications');
}

export function useNotifications(options: { readonly limit: number; readonly unreadOnly?: boolean; readonly poll?: boolean; readonly enabled?: boolean }) {
  const { userId } = useTenant();
  const unreadOnly = options.unreadOnly ?? false;
  const path = `/api/v1/me/notifications?limit=${String(options.limit)}${unreadOnly ? '&unreadOnly=true' : ''}`;
  return useQuery<NotificationPage>({
    queryKey: [...notificationsKey(userId), path],
    queryFn: ({ signal }) => apiFetch<NotificationPage>(path, { signal }),
    enabled: userId !== null && (options.enabled ?? true),
    ...(options.poll === true ? { refetchInterval: NOTIFICATION_POLL_MS } : {}),
  });
}

/** Marks the given notifications (or all with `null`) as read and refreshes every inbox view. */
export function useMarkNotificationsRead() {
  const { userId } = useTenant();
  const queryClient = useQueryClient();
  return useMutation<{ readonly unread: number }, Error, readonly string[] | null>({
    mutationFn: (ids) =>
      apiFetch<{ readonly unread: number }>('/api/v1/me/notifications/mark-read', {
        method: 'POST',
        body: ids === null ? { all: true } : { notificationIds: ids },
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: notificationsKey(userId) });
    },
  });
}
