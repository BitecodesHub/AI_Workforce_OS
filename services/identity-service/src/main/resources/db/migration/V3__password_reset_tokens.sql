-- Password reset links, created by a workspace administrator and handed to the person directly.
--
-- There is no outbound email, so a link is the whole mechanism, the same shape as an invitation:
-- a random token shown once, of which only the SHA-256 is kept. A database read must not be
-- enough to reset anybody's password. A link is single use and lives thirty minutes; used_at is
-- set when it is redeemed, or when a newer link for the same person replaces it.
--
-- The workspace and the issuer are checked again at redemption, not only at creation: within the
-- thirty minutes the person may join another workspace or become an owner, and the issuer may be
-- removed or lose member:update. issued_by is explicit because created_by is filled from the
-- request's actor and could read 'system'.
CREATE TABLE password_reset_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- The workspace whose administrator issued the link.
    org_id      UUID        NOT NULL,
    -- The person who issued the link.
    issued_by   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  TEXT        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    version     BIGINT      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  TEXT,
    updated_by  TEXT,
    CONSTRAINT password_reset_tokens_hash_unique UNIQUE (token_hash)
);

CREATE INDEX password_reset_tokens_open_idx ON password_reset_tokens (user_id) WHERE used_at IS NULL;

-- The signing key table was drafted for Ed25519 and still defaults to it, while every key this
-- platform stores is ES256 (TokenService explains the switch). The code sets the algorithm on
-- every insert; the default is corrected so a hand-written row cannot be mislabelled either.
ALTER TABLE signing_keys ALTER COLUMN algorithm SET DEFAULT 'ES256';
