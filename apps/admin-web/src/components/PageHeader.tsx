interface PageHeaderProps {
  readonly title: string;
}

export function PageHeader({ title }: PageHeaderProps) {
  return (
    <header className="vc-page-header">
      <h1>{title}</h1>
    </header>
  );
}
