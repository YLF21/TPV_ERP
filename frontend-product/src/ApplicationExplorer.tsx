import { ArrowRight, ChartBar, Check, CloudArrowUp, Cube, PlayCircle, ShoppingCart } from "@phosphor-icons/react";
import { useId, useRef, useState, type KeyboardEvent } from "react";
import { getApplicationGuides } from "./applicationGuides";
import { getConnectionContent } from "./connectionContent";
import { CompatibilityChecklist } from "./CompatibilityChecklist";
import { implementationJourneyId, openImplementationJourney } from "./ImplementationJourney";
import type { ApplicationId, GuideSection } from "./applicationGuideTypes";
import type { MarketingLanguage } from "./marketingI18n";
import { marketingHref } from "./marketingRoutes";
import { marketingContactSearch } from "./marketingCampaign";
import { getRovingTabIndex } from "./marketingAccessibility";
import "./application-guides.css";
import "./connection-summary.css";

type Application = {
  id: ApplicationId;
  name: string;
  label: string;
  description: string;
  previewAlt: string;
};

type ApplicationExplorerProps = {
  language: MarketingLanguage;
  applications: readonly Application[];
  onOpenTour: (application: ApplicationId) => void;
  onRequestDemo: (application: ApplicationId) => void;
};

const applicationOrder: readonly ApplicationId[] = ["venta", "gestion", "pda", "saas"];
const sections: readonly GuideSection[] = ["overview", "workflow", "setup", "questions"];
const applicationIcons = { venta: ShoppingCart, gestion: ChartBar, pda: Cube, saas: CloudArrowUp };
const previewDimensions = {
  venta: { width: 1600, height: 1000 },
  gestion: { width: 1600, height: 968 },
  pda: { width: 920, height: 1700 },
  saas: { width: 1600, height: 1000 }
};

function nextTabIndex(event: KeyboardEvent<HTMLButtonElement>, current: number, count: number) {
  const next = getRovingTabIndex(event.key, current, count);
  if (next !== null) event.preventDefault();
  return next;
}

export function ApplicationExplorer({ language, applications, onOpenTour, onRequestDemo }: ApplicationExplorerProps) {
  const instanceId = useId();
  const [activeApplication, setActiveApplication] = useState<ApplicationId>("venta");
  const [activeSection, setActiveSection] = useState<GuideSection>("overview");
  const applicationTabs = useRef<Partial<Record<ApplicationId, HTMLButtonElement | null>>>({});
  const sectionTabs = useRef<Partial<Record<GuideSection, HTMLButtonElement | null>>>({});
  const copy = getApplicationGuides(language);
  const connectionCopy = getConnectionContent(language);
  const availableApplications = applicationOrder.flatMap((id) => {
    const application = applications.find((item) => item.id === id);
    return application ? [application] : [];
  });
  const application = availableApplications.find((item) => item.id === activeApplication) ?? availableApplications[0];

  if (!application) return null;

  const guide = copy.apps[application.id];
  const demoHref = `${marketingHref(language, "contacto")}${marketingContactSearch([application.id])}#mk-demo-form`;
  const ApplicationIcon = applicationIcons[application.id];
  const applicationTabId = (id: ApplicationId) => `${instanceId}-application-tab-${id}`;
  const applicationPanelId = `${instanceId}-application-panel`;
  const sectionTabId = (section: GuideSection) => `${instanceId}-section-tab-${section}`;
  const sectionPanelId = `${instanceId}-section-panel`;

  function selectApplication(id: ApplicationId) {
    if (id === application.id) return;
    setActiveApplication(id);
    setActiveSection("overview");
  }

  return <>
    <section className="mk-app-explorer" data-testid="application-explorer" data-active-app={application.id} aria-label={copy.chooseLabel}>
      <div className="mk-app-explorer-tabs" role="tablist" aria-label={copy.chooseLabel}>
        {availableApplications.map((item, index) => {
          const TabIcon = applicationIcons[item.id];
          const selected = item.id === application.id;
          return <button
            key={item.id}
            ref={(element) => { applicationTabs.current[item.id] = element; }}
            type="button"
            id={applicationTabId(item.id)}
            data-testid={`guide-app-${item.id}`}
            data-app={item.id}
            role="tab"
            aria-selected={selected}
            aria-controls={applicationPanelId}
            tabIndex={selected ? 0 : -1}
            onClick={() => selectApplication(item.id)}
            onKeyDown={(event) => {
              const next = nextTabIndex(event, index, availableApplications.length);
              if (next === null) return;
              const id = availableApplications[next].id;
              selectApplication(id);
              applicationTabs.current[id]?.focus();
            }}
          ><TabIcon weight="duotone" aria-hidden="true" /><span><strong>{item.name}</strong><small>{item.label}</small></span></button>;
        })}
      </div>

      <div id={applicationPanelId} className="mk-app-explorer-panel" data-testid="application-guide-panel" role="tabpanel" aria-labelledby={applicationTabId(application.id)}>
        <header className="mk-app-explorer-intro">
          <div className="mk-app-explorer-app-icon"><ApplicationIcon weight="duotone" aria-hidden="true" /></div>
          <div><h2>{application.name}</h2><p>{guide.outcome}</p></div>
        </header>

        <div className="mk-app-explorer-body">
          <aside className="mk-app-explorer-context">
            <figure className="mk-app-explorer-preview">
              <div><img key={application.id} src={`/marketing/tour-${application.id}.png`} alt={application.previewAlt} {...previewDimensions[application.id]} loading="lazy" decoding="async" /></div>
              <figcaption>{copy.previewNote}</figcaption>
            </figure>
            <dl className="mk-app-explorer-facts">
              <div><dt>{copy.audienceLabel}</dt><dd>{guide.audience}</dd></div>
              <div><dt>{copy.worksWithLabel}</dt><dd>{guide.worksWith}</dd></div>
            </dl>
          </aside>

          <div className="mk-app-explorer-information">
            <p className="mk-app-explorer-description">{application.description}</p>
            <section className="mk-app-connection-summary" data-testid="application-connection-summary" aria-labelledby={`${instanceId}-connection-title`}>
              <h3 id={`${instanceId}-connection-title`}>{connectionCopy.appLabel}</h3>
              <p>{connectionCopy.apps[application.id]}</p>
            </section>
            <div className="mk-app-explorer-sections" role="tablist" aria-label={copy.sectionLabel}>
              {sections.map((section, index) => <button
                key={section}
                ref={(element) => { sectionTabs.current[section] = element; }}
                type="button"
                id={sectionTabId(section)}
                data-testid={`guide-section-${section}`}
                role="tab"
                aria-selected={activeSection === section}
                aria-controls={sectionPanelId}
                tabIndex={activeSection === section ? 0 : -1}
                onClick={() => setActiveSection(section)}
                onKeyDown={(event) => {
                  const next = nextTabIndex(event, index, sections.length);
                  if (next === null) return;
                  setActiveSection(sections[next]);
                  sectionTabs.current[sections[next]]?.focus();
                }}
              >{copy.sections[section]}</button>)}
            </div>

            <div id={sectionPanelId} className="mk-app-explorer-content" data-testid="guide-content-panel" data-section={activeSection} role="tabpanel" aria-labelledby={sectionTabId(activeSection)} tabIndex={0}>
              {activeSection === "overview" && <section aria-labelledby={`${instanceId}-tasks-title`}>
                <h3 id={`${instanceId}-tasks-title`}>{copy.tasksLabel}</h3>
                <ul className="mk-app-explorer-tasks">{guide.tasks.map((task) => <li key={task.title}><Check weight="bold" aria-hidden="true" /><div><h4>{task.title}</h4><p>{task.body}</p></div></li>)}</ul>
              </section>}

              {activeSection === "workflow" && <section aria-label={copy.sections.workflow}>
                <ol className="mk-app-explorer-workflow">{guide.workflow.map((step, index) => <li key={step.title}><span aria-hidden="true">{index + 1}</span><div><h4>{step.title}</h4><p>{step.body}</p></div></li>)}</ol>
                <p className="mk-app-explorer-example-note">{copy.exampleNote}</p>
              </section>}

              {activeSection === "setup" && <section className="mk-app-explorer-setup" aria-labelledby={`${instanceId}-requirements-title`}>
                <div><h3 id={`${instanceId}-requirements-title`}>{copy.requirementsLabel}</h3><ul>{guide.requirements.map((requirement) => <li key={requirement}><Check weight="bold" aria-hidden="true" /><span>{requirement}</span></li>)}</ul></div>
                <CompatibilityChecklist language={language} application={application.id} includeRequirements={false} />
              </section>}

              {activeSection === "questions" && <section className="mk-app-explorer-questions" aria-label={copy.sections.questions}>
                {guide.questions.map((question) => <details key={`${application.id}-${question.question}`}><summary>{question.question}</summary><p>{question.answer}</p></details>)}
              </section>}
            </div>

            <div className="mk-app-explorer-actions">
              <button className="mk-app-tour-action" type="button" onClick={() => onOpenTour(application.id)}><PlayCircle weight="bold" aria-hidden="true" />{copy.tourAction}</button>
              <a className="mk-app-explorer-demo" href={demoHref} onClick={(event) => {
                if (event.button !== 0 || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
                event.preventDefault();
                onRequestDemo(application.id);
              }}>{copy.demoAction}<ArrowRight aria-hidden="true" /></a>
              <a href={`${marketingHref(language, "apps")}#${implementationJourneyId}`} aria-controls={implementationJourneyId} data-testid="guide-preparation-action" onClick={(event) => {
                if (event.button !== 0 || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
                if (openImplementationJourney(document)) event.preventDefault();
              }}>{copy.consultAction}<ArrowRight aria-hidden="true" /></a>
            </div>
          </div>
        </div>
        <p className="mk-app-explorer-scope">{copy.scopeNote}</p>
      </div>
    </section>

    <section className="mk-guide-start" aria-labelledby={`${instanceId}-start-title`}>
      <div className="mk-guide-start-heading"><h2 id={`${instanceId}-start-title`}>{copy.gettingStarted.title}</h2><p>{copy.gettingStarted.body}</p></div>
      <ol className="mk-guide-start-steps">{copy.gettingStarted.steps.map((step, index) => <li key={step.title}><span aria-hidden="true">0{index + 1}</span><div><h3>{step.title}</h3><p>{step.body}</p></div></li>)}</ol>
      <p className="mk-guide-start-note">{copy.gettingStarted.note}</p>
    </section>
  </>;
}
