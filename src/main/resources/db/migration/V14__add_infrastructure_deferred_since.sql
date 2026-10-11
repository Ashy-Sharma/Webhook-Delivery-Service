ALTER TABLE deliveries
    ADD COLUMN infrastructure_deferred_since TIMESTAMP(6) NULL;

ALTER TABLE deliveries
    MODIFY COLUMN failure_reason VARCHAR(48) NULL;
