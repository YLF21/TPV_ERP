import type { apiRequest, UserSession } from "@tpverp/app-common";

export type MailProps = { session: UserSession; t: (key: string) => string; request: typeof apiRequest };
export type SmtpSettings = {
  configured: boolean; enabled: boolean; host: string | null; port: number;
  username: string | null; fromEmail: string | null; fromName: string | null;
  startTls: boolean; sslEnabled: boolean; passwordConfigured: boolean;
  credentialsReady: boolean; deliveryEnabled: boolean;
};
export type OAuthStatus = {
  providers: { provider: "GOOGLE" | "MICROSOFT"; available: boolean }[];
  connection: null | { provider: "GOOGLE" | "MICROSOFT"; email: string };
};
export type MailTemplate = {
  id: string; name: string; subject: string; heading: string; body: string;
  buttonText: string | null; buttonUrl: string | null; accentColor: string; logoUrl?: string | null; version: number;
};
export type TemplateDraft = Omit<MailTemplate, "id" | "version" | "buttonText" | "buttonUrl" | "logoUrl"> & { buttonText: string; buttonUrl: string; logoUrl: string };
export type MailPreview = { html: string; text: string };
export type Campaign = {
  id: string; name: string; templateId: string; status: string; scheduledAt: string | null;
  createdAt: string; version: number; recipientCount: number;
};
export type AudiencePreview = MailPreview & {
  previewToken: string;
  eligible: number; total: number; excludedNoConsent: number; excludedNoEmail: number;
  excludedChannel: number; excludedSuppressed: number; excludedInactive: number; excludedDuplicate: number;
};
export type CommunicationsStatus = { campaignsEnabled: boolean; publicUnsubscribeConfigured: boolean; workerEnabled: boolean };
export type MailDelivery = {
  id: string; campaignId?: string; campaignName?: string; email: string; subject?: string;
  status: string; createdAt: string; sentAt: string | null; errorMessage: string | null;
};
export type MemberSettings = {
  balanceAccrualEnabled: boolean; balanceAccrualBaseAmount: number | string;
  balanceAccrualPercent: number | string; balanceExpirationPolicy: string;
  pointsAccrualEnabled: boolean; pointsAccrualBaseAmount: number | string;
  pointsPerEuro: number | string; categoryAutoEnabled: boolean;
  memberWelcomeEnabled: boolean; memberCardCodeFormat: "QR" | "BARCODE";
  welcomeSubjectTemplate: string | null; welcomeBodyTemplate: string | null;
};
