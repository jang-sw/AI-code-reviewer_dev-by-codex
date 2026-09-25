create index review_issue_assignee_status_id_idx on review_issue(assignee_id, status, id desc);
create index review_issue_status_id_idx on review_issue(status, id desc);
