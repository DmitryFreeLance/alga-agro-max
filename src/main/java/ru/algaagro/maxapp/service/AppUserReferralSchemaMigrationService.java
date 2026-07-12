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
public class AppUserReferralSchemaMigrationService {

    private static final Logger log = LoggerFactory.getLogger(AppUserReferralSchemaMigrationService.class);

    private final DataSource dataSource;

    public AppUserReferralSchemaMigrationService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrateAppUserReferralColumns() {
        try (Connection connection = dataSource.getConnection()) {
            ensureColumn(connection, "app_users", "referred_by_max_user_id", "ALTER TABLE app_users ADD COLUMN referred_by_max_user_id bigint");
            ensureColumn(connection, "app_users", "referred_at", "ALTER TABLE app_users ADD COLUMN referred_at datetime");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to migrate app user referral schema", e);
        }
    }

    private void ensureColumn(Connection connection, String tableName, String columnName, String alterSql) throws Exception {
        String createSql = loadCreateSql(connection, tableName);
        if (createSql == null || createSql.isBlank()) {
            return;
        }
        Set<String> columns = loadColumns(connection, tableName);
        if (columns.contains(columnName)) {
            return;
        }
        log.info("Adding {} column to {}", columnName, tableName);
        try (Statement statement = connection.createStatement()) {
            statement.execute(alterSql);
        }
    }

    private String loadCreateSql(Connection connection, String tableName) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT sql FROM sqlite_master WHERE type='table' AND name='" + tableName + "'"
             )) {
            if (rs.next()) {
                return rs.getString(1);
            }
            return null;
        }
    }

    private Set<String> loadColumns(Connection connection, String tableName) throws Exception {
        Set<String> columns = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        return columns;
    }
}
