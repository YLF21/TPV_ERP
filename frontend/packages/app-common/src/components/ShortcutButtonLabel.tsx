/** Keep translated captions and keyboard shortcuts in separate button segments. */
export function ShortcutButtonLabel({ label, shortcut }: { label: string; shortcut: string }) {
  const suffix = label.endsWith(` (${shortcut})`) ? ` (${shortcut})`
    : label.endsWith(` ${shortcut}`) ? ` ${shortcut}` : "";
  const caption = suffix ? label.slice(0, -suffix.length) : label;
  return <><span>{caption}</span>{" "}<kbd>{shortcut}</kbd></>;
}
