import { useEffect, useRef, useState } from "react";
import { useOutsidePointerDown } from "./useOutsidePointerDown";

export type SaleCommandMenuEntry =
  | {
      type: "action";
      id: string;
      label: string;
      shortcut?: string;
      disabled?: boolean;
      disabledReason?: string;
      onSelect: () => void;
    }
  | {
      type: "toggle";
      id: string;
      label: string;
      checked: boolean;
      onToggle: () => void;
    }
  | {
      type: "separator";
      id: string;
    };

export type SaleCommandMenu = {
  id: string;
  label: string;
  entries: readonly SaleCommandMenuEntry[];
};

type Props = {
  ariaLabel: string;
  menus: readonly SaleCommandMenu[];
};

export function SaleCommandMenuBar({ ariaLabel, menus }: Props) {
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);
  const rootRef = useRef<HTMLElement | null>(null);
  const triggerRefs = useRef(new Map<string, HTMLButtonElement>());

  useOutsidePointerDown(Boolean(openMenuId), rootRef, () => setOpenMenuId(null));

  useEffect(() => {
    if (!openMenuId || !rootRef.current) return;
    const first = rootRef.current.querySelector<HTMLButtonElement>(
      `[data-sale-menu="${openMenuId}"] [role^="menuitem"]:not(:disabled)`
    );
    first?.focus();
  }, [openMenuId]);

  function focusTrigger(menuId: string) {
    triggerRefs.current.get(menuId)?.focus();
  }

  function closeMenu(restoreFocus = true) {
    const current = openMenuId;
    setOpenMenuId(null);
    if (restoreFocus && current) {
      queueMicrotask(() => focusTrigger(current));
    }
  }

  function moveToMenu(menuId: string, direction: -1 | 1) {
    const index = menus.findIndex((menu) => menu.id === menuId);
    if (index < 0) return;
    const next = menus[(index + direction + menus.length) % menus.length];
    setOpenMenuId(next.id);
  }

  function handleMenuKeyDown(
    event: React.KeyboardEvent<HTMLButtonElement>,
    menuId: string
  ) {
    const menu = event.currentTarget.closest<HTMLElement>('[role="menu"]');
    const items = Array.from(
      menu?.querySelectorAll<HTMLButtonElement>('[role^="menuitem"]:not(:disabled)') ?? []
    );
    const currentIndex = items.indexOf(event.currentTarget);
    if (event.key === "Escape") {
      event.preventDefault();
      closeMenu();
      return;
    }
    if (event.key === "ArrowRight" || event.key === "ArrowLeft") {
      event.preventDefault();
      moveToMenu(menuId, event.key === "ArrowRight" ? 1 : -1);
      return;
    }
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key) || items.length === 0) {
      return;
    }
    event.preventDefault();
    const nextIndex = event.key === "Home"
      ? 0
      : event.key === "End"
        ? items.length - 1
        : (currentIndex + (event.key === "ArrowDown" ? 1 : -1) + items.length) % items.length;
    items[nextIndex]?.focus();
  }

  return (
    <nav ref={rootRef} className="sale-command-menus" aria-label={ariaLabel}>
      {menus.map((menu) => {
        const open = openMenuId === menu.id;
        return (
          <div className="sale-command-menu" key={menu.id}>
            <button
              ref={(element) => {
                if (element) triggerRefs.current.set(menu.id, element);
                else triggerRefs.current.delete(menu.id);
              }}
              type="button"
              className={`sale-command-menu-trigger${open ? " open" : ""}`}
              aria-haspopup="menu"
              aria-expanded={open}
              aria-controls={`sale-command-menu-${menu.id}`}
              onClick={() => setOpenMenuId((current) => current === menu.id ? null : menu.id)}
              onKeyDown={(event) => {
                if (event.key === "ArrowRight" || event.key === "ArrowLeft") {
                  event.preventDefault();
                  const index = menus.findIndex((candidate) => candidate.id === menu.id);
                  const next = menus[
                    (index + (event.key === "ArrowRight" ? 1 : -1) + menus.length) % menus.length
                  ];
                  focusTrigger(next.id);
                  return;
                }
                if (!["ArrowDown", "Enter", " "].includes(event.key)) return;
                event.preventDefault();
                setOpenMenuId(menu.id);
              }}
            >
              <span>{menu.label}</span>
              <span aria-hidden="true">▾</span>
            </button>
            {open && (
              <div
                id={`sale-command-menu-${menu.id}`}
                data-sale-menu={menu.id}
                className="sale-command-menu-popover"
                role="menu"
                aria-label={menu.label}
              >
                {menu.entries.map((entry) => {
                  if (entry.type === "separator") {
                    return <div className="sale-command-menu-separator" role="separator" key={entry.id} />;
                  }
                  if (entry.type === "toggle") {
                    return (
                      <button
                        type="button"
                        role="menuitemcheckbox"
                        aria-checked={entry.checked}
                        className="sale-command-menu-item sale-command-menu-toggle"
                        key={entry.id}
                        onKeyDown={(event) => handleMenuKeyDown(event, menu.id)}
                        onClick={() => entry.onToggle()}
                      >
                        <span>{entry.label}</span>
                        <span className="sale-command-menu-check" aria-hidden="true">
                          {entry.checked ? "✓" : ""}
                        </span>
                      </button>
                    );
                  }
                  return (
                    <button
                      type="button"
                      role="menuitem"
                      aria-label={`${entry.label}${entry.shortcut ? ` ${entry.shortcut}` : ""}`}
                      className="sale-command-menu-item"
                      disabled={entry.disabled}
                      title={entry.disabled ? entry.disabledReason : undefined}
                      key={entry.id}
                      onKeyDown={(event) => handleMenuKeyDown(event, menu.id)}
                      onClick={() => {
                        closeMenu(false);
                        entry.onSelect();
                      }}
                    >
                      <span>{entry.label}</span>
                      {entry.shortcut && <kbd>{entry.shortcut}</kbd>}
                    </button>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}
    </nav>
  );
}
