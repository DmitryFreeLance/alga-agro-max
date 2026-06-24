package ru.algaagro.maxapp.service;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ImportJobSchemaMigrationService {

    private static final Logger log = LoggerFactory.getLogger(ImportJobSchemaMigrationService.class);

    private final DataSource dataSource;

    public ImportJobSchemaMigrationService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrateImportJobsTable() {
        try (Connection connection = dataSource.getConnection()) {
            String createSql = loadCreateSql(connection);
            if (createSql == null || createSql.isBlank()) {
                return;
            }

            Set<String> columns = loadColumns(connection);
            boolean hasPreviewJson = columns.contains("preview_json");
            boolean hasNewStatuses = createSql.contains("PREVIEW_READY") && createSql.contains("CANCELLED");
            if (hasPreviewJson && hasNewStatuses) {
                return;
            }

            log.info("Migrating import_jobs schema. hasPreviewJson={}, hasNewStatuses={}", hasPreviewJson, hasNewStatuses);
            rebuildImportJobsTable(connection, hasPreviewJson);
            log.info("import_jobs schema migration completed");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to migrate import_jobs schema", e);
        }
    }

    private String loadCreateSql(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='import_jobs'")) {
            if (rs.next()) {
                return rs.getString(1);
            }
            return null;
        }
    }

    private Set<String> loadColumns(Connection connection) throws Exception {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(import_jobs)")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        return columns;
    }

    private void rebuildImportJobsTable(Connection connection, boolean hasPreviewJson) throws Exception {
        boolean initialAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE import_jobs_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        initiated_by_max_user_id BIGINT NOT NULL,
                        status VARCHAR(255) NOT NULL CHECK (status IN ('PENDING','PROCESSING','PREVIEW_READY','COMPLETED','CANCELLED','FAILED')),
                        source_files_json CLOB,
                        summary CLOB,
                        preview_json CLOB,
                        created_at TIMESTAMP NOT NULL,
                        completed_at TIMESTAMP
                    )
                    """);

            String previewSelect = hasPreviewJson ? "COALESCE(preview_json, '[]')" : "'[]'";
            statement.execute("""
                    INSERT INTO import_jobs_new (
                        id,
                        initiated_by_max_user_id,
                        status,
                        source_files_json,
                        summary,
                        preview_json,
                        created_at,
                        completed_at
                    )
                    SELECT
                        id,
                        initiated_by_max_user_id,
                        CASE
                            WHEN status IN ('PENDING','PROCESSING','PREVIEW_READY','COMPLETED','CANCELLED','FAILED') THEN status
                            ELSE 'FAILED'
                        END,
                        source_files_json,
                        summary,
                        """ + previewSelect + """
                        ,
                        created_at,
                        completed_at
                    FROM import_jobs
                    """);
            statement.execute("DROP TABLE import_jobs");
            statement.execute("ALTER TABLE import_jobs_new RENAME TO import_jobs");
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(initialAutoCommit);
        }
    }
}
