CREATE TABLE customers (
    id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    email VARCHAR(320)
);

CREATE TABLE orders (
    id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    total DECIMAL(14,2) NOT NULL
);
