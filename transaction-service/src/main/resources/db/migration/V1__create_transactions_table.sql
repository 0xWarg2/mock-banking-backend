CREATE TABLE transactions (
    id              BIGSERIAL PRIMARY KEY,
    reference_id    VARCHAR(36) NOT NULL UNIQUE,
    from_account_id BIGINT NOT NULL,
    to_account_id   BIGINT NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL,
    currency        VARCHAR(3) NOT NULL DEFAULT 'VND',
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    description     VARCHAR(255),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transactions_reference_id ON transactions(reference_id);
CREATE INDEX idx_transactions_from_account ON transactions(from_account_id);
CREATE INDEX idx_transactions_to_account ON transactions(to_account_id);
CREATE INDEX idx_transactions_status ON transactions(status);
