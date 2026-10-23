-- A COMPLETED claim must not keep occupying the reservation slots.
-- A verified handover leaves its reports terminal, so it can never collide
-- with a later claim; but a LOST report resolved by a response approval can
-- be reopened, accept another response, and approve it again — which the old
-- unique keys rejected with a duplicate-key error.
ALTER TABLE claims DROP INDEX uk_claims_active_item;
ALTER TABLE claims DROP INDEX uk_claims_active_lost_report;

ALTER TABLE claims
    MODIFY COLUMN active_item_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN status = 'APPROVED' THEN item_id ELSE NULL END) STORED,
    MODIFY COLUMN active_lost_report_id BIGINT
        GENERATED ALWAYS AS (CASE WHEN status = 'APPROVED' THEN lost_report_id ELSE NULL END) STORED;

ALTER TABLE claims
    ADD UNIQUE KEY uk_claims_active_item (active_item_id),
    ADD UNIQUE KEY uk_claims_active_lost_report (active_lost_report_id);
