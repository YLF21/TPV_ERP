import type { AppId } from "./applicationMetadata";
import type { MarketingLanguage } from "./marketingI18n";
import { getTourStepMedia } from "./tourStepMedia";

export function TourStepPreview({ application, step, language }: {
  application: AppId;
  step: number;
  language: MarketingLanguage;
}) {
  const media = getTourStepMedia(application, step, language);

  return <figure className="mk-tour-preview" data-testid="tour-step-preview" data-media-kind={media.kind} data-step={media.step}>
    <img key={`${application}-${step}-${media.src}`} src={media.src} alt={media.alt} width={media.width} height={media.height} decoding="async" />
    <figcaption>
      <span>{media.caption}</span>{" "}
      <a className="mk-service-action" href={media.src} target="_blank" rel="noreferrer">{media.expandLabel}</a>
    </figcaption>
  </figure>;
}
