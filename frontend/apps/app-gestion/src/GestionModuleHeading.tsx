import type { ReactNode } from "react";

type GestionModuleHeadingProps = {
  group: string;
  title?: string;
  description?: string;
  children?: ReactNode;
  className?: string;
  id?: string;
};

export function GestionModuleHeading({ group, title, description, children, className = "", id }: GestionModuleHeadingProps) {
  return (
    <header className={`gestion-module-heading ${className}`.trim()}>
      <h1 id={title ? undefined : id} className="gestion-module-heading-group">{group}</h1>
      {(title || description || children) && <div className="gestion-module-heading-row">
        <div className="gestion-module-heading-copy">
          {title && <h2 id={id}>{title}</h2>}
          {description && <p>{description}</p>}
        </div>
        {children && <div className="gestion-module-heading-actions management-record-actions gestion-toolbar-actions">{children}</div>}
      </div>}
    </header>
  );
}
