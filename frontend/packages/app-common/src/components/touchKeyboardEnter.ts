type TextField = HTMLInputElement | HTMLTextAreaElement;

/** Route the virtual key through the same field/window handlers as physical Enter. */
export function touchKeyboardEnter(field: TextField | null | undefined, insertNewline?: () => void) {
  if (!field?.isConnected || field.readOnly || field.matches(":disabled") ||
      field.closest('[hidden], [inert], [aria-hidden="true"]')) return;
  field.focus({ preventScroll: true });
  const event = new KeyboardEvent("keydown", { key: "Enter", code: "Enter", bubbles: true, cancelable: true });
  field.dispatchEvent(event);
  if (!event.defaultPrevented && field.isConnected && !field.matches(":disabled")) {
    if (field instanceof HTMLTextAreaElement) insertNewline?.();
    else {
      // Synthetic key events have no browser implicit-submit default. Use the existing
      // enabled submit control so its click handler and normal form validation still apply.
      const form = field.form;
      const submit = form?.querySelector<HTMLButtonElement | HTMLInputElement>(
        'button[type="submit"], button:not([type]), input[type="submit"]',
      );
      if (submit && !submit.matches(":disabled") && !submit.closest('[hidden], [inert], [aria-hidden="true"]')) {
        submit.click();
      } else if (form && !submit) {
        const blockingFields = Array.from(form.elements).filter((control) => control instanceof HTMLInputElement &&
          /^(text|search|url|tel|email|password|date|month|week|time|datetime-local|number)$/.test(control.type));
        if (blockingFields.length <= 1) form.requestSubmit();
      }
    }
  }
  if (field.isConnected) field.dispatchEvent(new KeyboardEvent("keyup", {
    key: "Enter", code: "Enter", bubbles: true, cancelable: true,
  }));
}
