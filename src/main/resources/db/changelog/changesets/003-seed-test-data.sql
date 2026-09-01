--liquibase formatted sql

--changeset rudhra:003-seed-customers

INSERT INTO customers (
    name,
    email,
    country,
    created_at
)
SELECT
    'Customer ' || i,
    'customer' || i || '@example.com',
    CASE (i % 5)
        WHEN 0 THEN 'India'
        WHEN 1 THEN 'USA'
        WHEN 2 THEN 'Canada'
        WHEN 3 THEN 'Germany'
        ELSE 'Australia'
    END,
    NOW() - (i % 365) * INTERVAL '1 day'
FROM generate_series(1, 100000) AS i;


--changeset rudhra:004-seed-orders

INSERT INTO orders (
    customer_id,
    status,
    amount,
    created_at
)
SELECT
    ((i - 1) % 100000) + 1,

    CASE (i % 4)
        WHEN 0 THEN 'COMPLETED'
        WHEN 1 THEN 'PENDING'
        WHEN 2 THEN 'FAILED'
        ELSE 'PROCESSING'
    END,

    ROUND((10 + RANDOM() * 10000)::numeric, 2),

    NOW() - (i % 730) * INTERVAL '1 day'

FROM generate_series(1, 1000000) AS i;