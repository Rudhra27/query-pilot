--liquibase formatted sql

--changeset rudhra:001-create-customers-table
CREATE TABLE customers (
    id         BIGSERIAL    NOT NULL,
    name       VARCHAR(255) NOT NULL,
    email      VARCHAR(255) NOT NULL,
    country    VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_customers PRIMARY KEY (id)
);
--rollback DROP TABLE customers;
