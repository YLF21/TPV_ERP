import { CaretDown } from "@phosphor-icons/react";
import { useEffect, useRef } from "react";
import { getImplementationJourney, implementationStepIds } from "./implementationContent";
import type { MarketingLanguage } from "./marketingI18n";

export const implementationJourneyId = "mk-implementation-journey";

/** Reveal existing guidance only; this action never requests a demo or changes route. */
export function openImplementationJourney(root: Pick<Document, "getElementById">): boolean {
  const target = root.getElementById(implementationJourneyId);
  if (target?.tagName !== "DETAILS") return false;
  const journey = target as HTMLDetailsElement;
  journey.open = true;
  journey.querySelector("summary")?.focus({ preventScroll: true });
  const page = journey.closest<HTMLElement>(".mk-page");
  if (page) {
    const headerHeight = page.querySelector(".mk-header")?.getBoundingClientRect().height ?? 0;
    const top = page.scrollTop + journey.getBoundingClientRect().top - page.getBoundingClientRect().top - headerHeight - 12;
    // The page has CSS smooth scrolling; instant also respects reduced-motion users.
    page.scrollTo({ top: Math.max(0, top), behavior: "instant" });
  } else {
    journey.scrollIntoView({ block: "start", behavior: "instant" });
  }
  return true;
}

export function ImplementationJourney({ language }: { language: MarketingLanguage }) {
  const copy = getImplementationJourney(language);
  const journeyRef = useRef<HTMLDetailsElement | null>(null);

  useEffect(() => {
    let frame: number | undefined;
    function revealFragment() {
      if (window.location.hash !== `#${implementationJourneyId}`) return;
      // Run after the page's initial scroll reset when opened via a native link/new tab.
      frame = window.requestAnimationFrame(() => {
        const root = journeyRef.current?.ownerDocument;
        if (root) openImplementationJourney(root);
      });
    }
    revealFragment();
    window.addEventListener("hashchange", revealFragment);
    return () => {
      if (frame !== undefined) window.cancelAnimationFrame(frame);
      window.removeEventListener("hashchange", revealFragment);
    };
  }, []);

  return <details id={implementationJourneyId} ref={journeyRef} className="mk-service-disclosure" data-testid="implementation-journey">
    <summary>
      <span><strong>{copy.title}</strong><span>{copy.intro}</span></span>
      <CaretDown aria-hidden="true" />
    </summary>
    <div className="mk-service-content">
      <ol className="mk-service-steps">
        {implementationStepIds.map((id) => {
          const step = copy.steps[id];
          return <li key={id} data-implementation-step={id}>
            <h3>{step.title}</h3>
            <p>{step.body}</p>
            <dl className="mk-service-facts">
              <div><dt>{copy.preparationLabel}</dt><dd>{step.customerPreparation}</dd></div>
              <div><dt>{copy.confirmationLabel}</dt><dd>{step.confirmation}</dd></div>
            </dl>
          </li>;
        })}
      </ol>
      <p className="mk-service-note">{copy.note}</p>
    </div>
  </details>;
}
