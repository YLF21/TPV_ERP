alter table saas_support_intervention drop constraint saas_support_intervention_status_check;
alter table saas_support_intervention add constraint saas_support_intervention_status_check
 check (status in ('REMOTE_PENDING','REMOTE_IN_PROGRESS','ONSITE_REQUIRED','ONSITE_IN_PROGRESS','AWAITING_VERIFICATION','RESOLVED'));
alter table saas_support_intervention add column assignee varchar(120), add column visit_at timestamptz,
 add column resolution_summary varchar(2000), add column verification_notes varchar(2000), add column confirmed_by varchar(120);
alter table saas_support_intervention_event drop constraint saas_support_intervention_event_status_check;
alter table saas_support_intervention_event drop constraint saas_support_intervention_event_action_check;
alter table saas_support_intervention_event add constraint saas_support_intervention_event_status_check
 check (status in ('REMOTE_PENDING','REMOTE_IN_PROGRESS','ONSITE_REQUIRED','ONSITE_IN_PROGRESS','AWAITING_VERIFICATION','RESOLVED'));
alter table saas_support_intervention_event add constraint saas_support_intervention_event_action_check
 check (action in ('START_REMOTE','REQUIRE_ONSITE','START_ONSITE','REQUEST_VERIFICATION','VERIFICATION_FAILED','RESOLVE','REOPEN','SAVE_DETAILS'));
alter table saas_support_intervention_event add column assignee varchar(120), add column visit_at timestamptz,
 add column resolution_summary varchar(2000), add column verification_notes varchar(2000), add column confirmed_by varchar(120),
 add column request_fingerprint varchar(64);
