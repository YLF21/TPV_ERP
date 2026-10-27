export type ApplicationId = "venta" | "gestion" | "pda" | "saas";
export type GuideSection = "overview" | "workflow" | "setup" | "questions";
export type GuideItem = { title: string; body: string };

export type ApplicationGuide = {
  outcome: string;
  audience: string;
  tasks: readonly GuideItem[];
  workflow: readonly GuideItem[];
  requirements: readonly string[];
  worksWith: string;
  questions: readonly { question: string; answer: string }[];
};

export type ApplicationGuideContent = {
  chooseLabel: string;
  sectionLabel: string;
  sections: Record<GuideSection, string>;
  audienceLabel: string;
  tasksLabel: string;
  worksWithLabel: string;
  requirementsLabel: string;
  exampleNote: string;
  previewNote: string;
  tourAction: string;
  demoAction: string;
  consultAction: string;
  scopeNote: string;
  apps: Record<ApplicationId, ApplicationGuide>;
  gettingStarted: { title: string; body: string; steps: readonly GuideItem[]; note: string };
};
