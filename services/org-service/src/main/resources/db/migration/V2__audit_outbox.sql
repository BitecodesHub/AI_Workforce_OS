-- Audit events waiting to be delivered to analytics-service.
--
-- A service that changes something the audit log must remember writes the event here, in the same
-- transaction as the change, and a relay delivers it afterwards and removes it. The two commit
-- together: a change that rolled back leaves no event, and a change that committed cannot lose its
-- event to an outage of analytics-service, because the event is already stored where the change is.
--
-- The id is the event's identity, fixed before the first delivery attempt and sent with every one,
-- which is how analytics-service recognises a retry and appends the event once.
CREATE TABLE audit_outbox (
    id              UUID PRIMARY KEY,
    org_id          UUID,
    actor_id        TEXT        NOT NULL,
    actor_kind      TEXT        NOT NULL,
    on_behalf_of    TEXT,
    action          TEXT        NOT NULL,
    resource_type   TEXT        NOT NULL,
    resource_id     TEXT,
    outcome         TEXT        NOT NULL,
    detail          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    request_id      TEXT,
    -- When the action happened. A delivery that waited keeps this, rather than the time it got through.
    occurred_at     TIMESTAMPTZ NOT NULL,
    attempts        INT         NOT NULL DEFAULT 0,
    -- Not tried before this. Pushed out while a relay is sending the row, so two instances do not
    -- send it at once, and after a failure, for the backoff.
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX audit_outbox_due_idx ON audit_outbox (next_attempt_at);
