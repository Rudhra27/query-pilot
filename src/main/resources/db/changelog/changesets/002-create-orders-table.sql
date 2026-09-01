--liquibase formatted sql

--changeset rudhra:002-create-orders-table
CREATE TABLE orders (
    id          BIGSERIAL      NOT NULL,
    customer_id BIGINT         NOT NULL,
    status      VARCHAR(50)    NOT NULL,
    amount      NUMERIC(12, 2) NOT NULL,
    created_at  TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
);
--rollback DROP TABLE orders;
