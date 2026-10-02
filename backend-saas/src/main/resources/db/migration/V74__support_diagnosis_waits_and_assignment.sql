alter table saas_support_intervention drop constraint saas_support_intervention_status_check;
alter table saas_support_intervention add constraint saas_support_intervention_status_check
 check (status in ('REMOTE_PENDING','SAAS_IN_PROGRESS','REMOTE_IN_PROGRESS','ONSITE_REQUIRED','ONSITE_IN_PROGRESS','AWAITING_VERIFICATION','WAITING_CUSTOMER','WAITING_MATERIAL','RESOLVED'));
alter table saas_support_intervention add column assignee_user_id uuid references saas_admin_user(id),
 add column next_review_at timestamptz, add column resume_status varchar(40);
alter table saas_support_intervention add constraint saas_support_intervention_wait_check check (
 (status in ('WAITING_CUSTOMER','WAITING_MATERIAL') and next_review_at is not null and resume_status is not null
  and resume_status in ('REMOTE_PENDING','SAAS_IN_PROGRESS','REMOTE_IN_PROGRESS','ONSITE_REQUIRED','ONSITE_IN_PROGRESS','AWAITING_VERIFICATION'))
 or (status not in ('WAITING_CUSTOMER','WAITING_MATERIAL') and next_review_at is null and resume_status is null));
alter table saas_support_intervention_event drop constraint saas_support_intervention_event_status_check;
alter table saas_support_intervention_event drop constraint saas_support_intervention_event_action_check;
alter table saas_support_intervention_event add constraint saas_support_intervention_event_status_check
 check (status in ('REMOTE_PENDING','SAAS_IN_PROGRESS','REMOTE_IN_PROGRESS','ONSITE_REQUIRED','ONSITE_IN_PROGRESS','AWAITING_VERIFICATION','WAITING_CUSTOMER','WAITING_MATERIAL','RESOLVED'));
alter table saas_support_intervention_event add constraint saas_support_intervention_event_action_check
 check (action in ('START_SAAS','START_REMOTE','REQUIRE_ONSITE','START_ONSITE','REQUEST_VERIFICATION','VERIFICATION_FAILED','RESOLVE','REOPEN','SAVE_DETAILS','WAIT_CUSTOMER','WAIT_MATERIAL','RESUME'));
-- Event identifiers are immutable snapshots; historical names remain even if the user changes.
alter table saas_support_intervention_event add column assignee_user_id uuid,
 add column next_review_at timestamptz, add column resume_status varchar(40);
create index saas_support_intervention_assignee_idx on saas_support_intervention(assignee_user_id);
create index saas_support_intervention_review_idx on saas_support_intervention(next_review_at) where next_review_at is not null;
