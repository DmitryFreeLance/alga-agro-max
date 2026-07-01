package ru.algaagro.maxapp.service;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BotSessionSchemaMigrationService {

    private static final Logger log = LoggerFactory.getLogger(BotSessionSchemaMigrationService.class);

    private final DataSource dataSource;

    public BotSessionSchemaMigrationService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrateBotSessionsTable() {
        try (Connection connection = dataSource.getConnection()) {
            String createSql = loadCreateSql(connection);
            if (createSql == null || createSql.isBlank()) {
                return;
            }
            boolean hasBroadcastStates = createSql.contains("BROADCAST_WAITING_MEDIA")
                    && createSql.contains("BROADCAST_WAITING_TEXT");
            if (hasBroadcastStates) {
                return;
            }
            log.info("Migrating bot_sessions schema to support broadcast states");
            rebuildBotSessionsTable(connection);
            log.info("bot_sessions schema migration completed");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to migrate bot_sessions schema", e);
        }
    }

    private String loadCreateSql(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='bot_sessions'")) {
            if (rs.next()) {
                return rs.getString(1);
            }
            return null;
        }
    }

    private void rebuildBotSessionsTable(Connection connection) throws Exception {
        boolean initialAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE bot_sessions_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        max_user_id BIGINT NOT NULL UNIQUE,
                        state VARCHAR(255) NOT NULL CHECK (state IN (
                            'IDLE',
                            'IMPORT_WAITING_FILES',
                            'POST_WAITING_MEDIA',
                            'POST_WAITING_TEXT',
                            'BROADCAST_WAITING_MEDIA',
                            'BROADCAST_WAITING_TEXT'
                        )),
                        payload_json CLOB NOT NULL,
                        updated_at TIMESTAMP NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO bot_sessions_new (
                        id,
                        max_user_id,
                        state,
                        payload_json,
                        updated_at
                    )
                    SELECT
                        id,
                        max_user_id,
                        CASE
                            WHEN state IN (
                                'IDLE',
                                'IMPORT_WAITING_FILES',
                                'POST_WAITING_MEDIA',
                                'POST_WAITING_TEXT',
                                'BROADCAST_WAITING_MEDIA',
                                'BROADCAST_WAITING_TEXT'
                            ) THEN state
                            ELSE 'IDLE'
                        END,
                        COALESCE(payload_json, '{}'),
                        updated_at
                    FROM bot_sessions
                    """);
            statement.execute("DROP TABLE bot_sessions");
            statement.execute("ALTER TABLE bot_sessions_new RENAME TO bot_sessions");
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(initialAutoCommit);
        }
    }
}
