CREATE TABLE IF NOT EXISTS products (
    id BIGINT PRIMARY KEY,
    name TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

TRUNCATE TABLE products;
INSERT INTO products (id, name, active) VALUES
    (1, 'starter', TRUE),
    (2, 'pro', TRUE),
    (3, 'retired', FALSE);

CREATE TABLE IF NOT EXISTS release_probe (
    id BIGINT PRIMARY KEY,
    note TEXT NOT NULL
);

TRUNCATE TABLE release_probe;
