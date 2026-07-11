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
public class OrderCurrencySchemaMigrationService {

    private static final Logger log = LoggerFactory.getLogger(OrderCurrencySchemaMigrationService.class);

    private final DataSource dataSource;

    public OrderCurrencySchemaMigrationService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrateOrderCurrencyColumns() {
        try (Connection connection = dataSource.getConnection()) {
            ensureCurrencyColumn(connection, "catalog_orders");
            ensureCurrencyColumn(connection, "catalog_order_items");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to migrate order currency schema", e);
        }
    }

    private void ensureCurrencyColumn(Connection connection, String tableName) throws Exception {
        String createSql = loadCreateSql(connection, tableName);
        if (createSql == null || createSql.isBlank()) {
            return;
        }

        Set<String> columns = loadColumns(connection, tableName);
        if (columns.contains("currency_code")) {
            return;
        }

        log.info("Adding currency_code column to {}", tableName);
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE " + tableName + " ADD COLUMN currency_code varchar(255) NOT NULL DEFAULT 'RUB'"
            );
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
