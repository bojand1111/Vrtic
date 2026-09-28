import './schedules.css';

export interface TabItem<K extends string> {
  readonly key: K;
  readonly label: string;
}

/** Accessible tab strip (role=tablist); the caller renders the panel with `tabPanelProps` (tabPanel.ts). */
export function Tabs<K extends string>({ id, label, items, active, onChange }: { readonly id: string; readonly label: string; readonly items: readonly TabItem<K>[]; readonly active: K; readonly onChange: (key: K) => void }) {
  return (
    <div className="vc-tabs" role="tablist" aria-label={label}>
      {items.map((item) => (
        <button
          key={item.key}
          type="button"
          role="tab"
          id={`${id}-tab-${item.key}`}
          aria-selected={active === item.key}
          aria-controls={`${id}-panel-${item.key}`}
          className={active === item.key ? 'vc-tab vc-tab--active' : 'vc-tab'}
          onClick={() => {
            onChange(item.key);
          }}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}
