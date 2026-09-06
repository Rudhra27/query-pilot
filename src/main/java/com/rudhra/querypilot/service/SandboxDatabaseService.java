package com.rudhra.querypilot.service;

import com.rudhra.querypilot.config.SandboxProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SandboxDatabaseService {

    private final SandboxProperties sandboxProperties;

    public SandboxDatabaseService(
            SandboxProperties sandboxProperties
    ) {
        this.sandboxProperties = sandboxProperties;
    }

    public boolean testAdminConnection() {
        JdbcTemplate jdbcTemplate = createAdminJdbcTemplate();
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return result != null && result == 1;
    }

    public boolean testSandboxConnection() {
        JdbcTemplate sandboxJdbcTemplate = getSandboxJdbcTemplate();

        Integer result = sandboxJdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return result != null && result == 1;
    }

    private JdbcTemplate createAdminJdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(sandboxProperties.adminUrl());

        dataSource.setUsername(sandboxProperties.adminUsername());
        dataSource.setPassword(sandboxProperties.adminPassword());

        return new JdbcTemplate(dataSource);
    }

    private JdbcTemplate createSandboxAdminJdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(sandboxProperties.jdbcUrl());

        dataSource.setUsername(sandboxProperties.adminUsername());
        dataSource.setPassword(sandboxProperties.adminPassword());

        return new JdbcTemplate(dataSource);
    }

    public void createFreshSandbox() {

        JdbcTemplate adminJdbcTemplate = createAdminJdbcTemplate();
        String sandboxDatabase = sandboxProperties.databaseName();
        String baselineDatabase = sandboxProperties.baselineDatabaseName();

        terminateConnections(adminJdbcTemplate, sandboxDatabase);
        dropSandboxIfExists(adminJdbcTemplate, sandboxDatabase);
        terminateConnections(adminJdbcTemplate, baselineDatabase);
        createSandbox(adminJdbcTemplate, sandboxDatabase, baselineDatabase);
        transferSandboxTableOwnership();
    }

    /**
     * The sandbox connection runs as the least-privilege querypilot_sandbox
     * role so it can be denied CONNECT to the primary database. On
     * PostgreSQL < 17 CREATE/DROP INDEX requires table ownership (there is
     * no standalone grantable privilege for it yet), so ownership of the
     * freshly cloned tables is handed to that role here, using the
     * superuser admin connection, immediately after each clone.
     */
    private void transferSandboxTableOwnership() {
        JdbcTemplate sandboxAdminJdbcTemplate = createSandboxAdminJdbcTemplate();
        String sandboxRole = sandboxProperties.username();

        sandboxAdminJdbcTemplate.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + sandboxRole);

        List<String> tables = sandboxAdminJdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'",
                String.class
        );

        for (String table : tables) {
            sandboxAdminJdbcTemplate.execute("ALTER TABLE " + table + " OWNER TO " + sandboxRole);
        }
    }
    private void terminateConnections(JdbcTemplate jdbcTemplate, String databaseName) {
        jdbcTemplate.queryForList(
                """
                SELECT pg_terminate_backend(pid)
                FROM pg_stat_activity
                WHERE datname = ?
                AND pid <> pg_backend_pid()
                """,
                databaseName
        );
    }
    private void dropSandboxIfExists(JdbcTemplate jdbcTemplate, String databaseName) {
        Boolean exists = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_database
                    WHERE datname = ?
                )
                """,
                Boolean.class,
                databaseName
        );

        if (Boolean.TRUE.equals(exists)) {
            jdbcTemplate.execute("DROP DATABASE " + databaseName);
        }
    }
    private void createSandbox(JdbcTemplate jdbcTemplate, String sandboxDatabase, String baselineDatabase) {

        jdbcTemplate.execute("CREATE DATABASE " + sandboxDatabase + " WITH TEMPLATE " + baselineDatabase);
    }

    public JdbcTemplate getSandboxJdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(sandboxProperties.jdbcUrl());

        dataSource.setUsername(sandboxProperties.username());
        dataSource.setPassword(sandboxProperties.password());

        return new JdbcTemplate(dataSource);
    }
}