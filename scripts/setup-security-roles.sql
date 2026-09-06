-- QueryPilot least-privilege roles.
--
-- The application previously ran everything (including EXPLAIN ANALYZE of
-- arbitrary user-submitted SQL) as the `querypilot` role, which is a full
-- Postgres superuser. This splits access into three roles by purpose:
--
--   querypilot           - existing superuser. Kept only for Liquibase
--                           migrations and for the sandbox admin connection
--                           (which must DROP/CREATE the sandbox database).
--   querypilot_readonly  - used by the app's primary connection, which
--                           executes raw user-submitted SQL via
--                           EXPLAIN ANALYZE and benchmarking. SELECT only,
--                           plus default_transaction_read_only as a second
--                           line of defense against GRANT mistakes.
--   querypilot_sandbox   - used only for the disposable querypilot_sandbox
--                           database, where CREATE INDEX / DROP INDEX must
--                           work to validate optimization candidates. On
--                           PostgreSQL < 17 there is no grantable "create
--                           index" privilege independent of table ownership
--                           (that arrives with the MAINTAIN privilege in
--                           PG17). Granting it membership in the `querypilot`
--                           superuser role was tried and rejected: inherited
--                           superuser status bypasses ALL privilege checks,
--                           including the CONNECT revoke below, so it could
--                           still reach the primary database. Instead,
--                           SandboxDatabaseService transfers ownership of
--                           the sandbox tables to this role immediately
--                           after every fresh clone (see
--                           transferSandboxTableOwnership()). It never
--                           inherits from a superuser role. Its blast
--                           radius is contained by revoking CONNECT to
--                           every other database, and because the sandbox
--                           database is dropped and recreated from the
--                           `querypilot` template before every validation
--                           run.
--
-- IMPORTANT: Postgres grants CONNECT to the PUBLIC pseudo-role on every
-- database by default. Revoking CONNECT from a specific role is a no-op
-- unless PUBLIC's default grant is revoked first and re-granted explicitly
-- to the roles that should keep it.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'querypilot_readonly') THEN
        CREATE ROLE querypilot_readonly LOGIN PASSWORD 'querypilot_readonly_pw';
    END IF;
END
$$;

ALTER ROLE querypilot_readonly SET default_transaction_read_only = on;

REVOKE CONNECT ON DATABASE querypilot FROM PUBLIC;
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;

GRANT CONNECT ON DATABASE querypilot TO querypilot_readonly;
GRANT USAGE ON SCHEMA public TO querypilot_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO querypilot_readonly;
ALTER DEFAULT PRIVILEGES FOR ROLE querypilot IN SCHEMA public
    GRANT SELECT ON TABLES TO querypilot_readonly;

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'querypilot_sandbox') THEN
        CREATE ROLE querypilot_sandbox LOGIN PASSWORD 'querypilot_sandbox_pw';
    END IF;
END
$$;

-- No membership grant here on purpose - see comment above. Table
-- ownership within each fresh sandbox clone is transferred at runtime
-- by SandboxDatabaseService using the querypilot admin connection.
