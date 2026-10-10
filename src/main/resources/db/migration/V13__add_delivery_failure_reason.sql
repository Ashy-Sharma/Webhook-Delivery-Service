alter table deliveries
    add column failure_reason varchar(32) null;

update deliveries
set failure_reason = 'HTTP_EXHAUSTED'
where status = 'FAILED'
  and attempt_count >= max_attempts;
