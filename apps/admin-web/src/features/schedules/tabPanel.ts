/** Props of the panel that belongs to a tab of [Tabs] with the same `id`. */
export function tabPanelProps(id: string, key: string) {
  return { role: 'tabpanel', id: `${id}-panel-${key}`, 'aria-labelledby': `${id}-tab-${key}` } as const;
}
