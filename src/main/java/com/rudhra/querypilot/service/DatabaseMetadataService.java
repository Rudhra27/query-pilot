package com.rudhra.querypilot.service;

import com.rudhra.querypilot.dto.IndexMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class DatabaseMetadataService {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseMetadataService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<IndexMetadata> getIndexes(String tableName) {
        String sql = getSql();

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> {
                    String columns = rs.getString("columns");
                    List<String> columnList =
                            columns == null || columns.isBlank()
                                    ? List.of()
                                    : Arrays.stream(columns.split(","))
                                    .map(String::trim)
                                    .toList();

                    return new IndexMetadata(
                            rs.getString("indexname"),
                            columnList,
                            rs.getString("indexdef").startsWith("CREATE UNIQUE"),
                            rs.getString("indexdef")
                    );
                },
                tableName
        );
    }

    public List<String> getColumns(String tableName) {
        String sql = """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                AND table_name = ?
                ORDER BY ordinal_position
                """;

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getString("column_name"),
                tableName
        );
    }

    private String getSql() {
        return """
                SELECT
                    indexname,
                    indexdef,
                    regexp_replace(
                        substring(
                            indexdef FROM '\\((.*)\\)'
                        ),
                        '[()]',
                        '',
                        'g'
                    ) AS columns
                FROM pg_indexes
                WHERE schemaname = 'public'
                AND tablename = ?
                """;
    }
}