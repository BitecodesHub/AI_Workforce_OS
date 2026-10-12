-- Push notifications on a person's own devices (Web Push). One row per browser and workspace that
-- agreed to receive them: where its push service delivers (the endpoint, which a browser hands each
-- site once, so it is unique within a workspace), the two keys a message is encrypted to, the
-- sign-in session that saved it, and how delivery has gone. A person who works in several
-- workspaces has one row in each for the same browser. A message carries a sentence and a link,
-- never what an agent wanted to send.
CREATE TABLE push_subscriptions (
    id               UUID PRIMARY KEY,
    org_id           UUID        NOT NULL,
    user_id          UUID        NOT NULL,
    endpoint         TEXT        NOT NULL,
    -- The browser's P-256 public key (65 bytes) and its 16-byte authentication secret, base64url.
    p256dh           TEXT        NOT NULL,
    auth             TEXT        NOT NULL,
    -- "Chrome on Android": how the person tells their devices apart on the settings page.
    user_agent_label TEXT        NOT NULL DEFAULT '',
    -- The identity session (the access token's sid) that last saved this row. A device whose
    -- sign-in has ended (signed out, signed out everywhere, password changed) stops receiving.
    session_id       UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_success_at  TIMESTAMPTZ,
    -- Failed deliveries in a row; a subscription that keeps failing is removed.
    failure_count    INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT push_subscriptions_org_endpoint_key UNIQUE (org_id, endpoint),
    CONSTRAINT push_subscriptions_failure_count_check CHECK (failure_count >= 0)
);

CREATE INDEX push_subscriptions_org_user_idx ON push_subscriptions (org_id, user_id);

-- The server's own VAPID identity, which every subscription is bound to: generated once, kept for
-- good. Exactly one row. The private key is stored encrypted under the platform master key; the
-- public key is handed to every browser that subscribes.
CREATE TABLE push_vapid_keys (
    id                    SMALLINT PRIMARY KEY,
    public_key            TEXT        NOT NULL,
    private_key_encrypted TEXT        NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT push_vapid_keys_single_row CHECK (id = 1)
);
