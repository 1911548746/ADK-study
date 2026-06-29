package com.hh.examples.study000.persistentCli.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Study000SchemaInitializer implements InitializingBean {

    private final JdbcTemplate jdbcTemplate;

    public Study000SchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        // 这里只负责创建表；数据库 persistentCli 由学习者提前创建，便于明确观察库和表。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    app_name VARCHAR(128) NOT NULL,
                    user_id VARCHAR(128) NOT NULL,
                    session_id VARCHAR(128) NOT NULL,
                    state_json JSON NULL,
                    created_at DATETIME NOT NULL,
                    updated_at DATETIME NOT NULL,
                    UNIQUE KEY uk_sessions_identity (app_name, user_id, session_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS session_events (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    app_name VARCHAR(128) NOT NULL,
                    user_id VARCHAR(128) NOT NULL,
                    session_id VARCHAR(128) NOT NULL,
                    event_id VARCHAR(128) NOT NULL,
                    invocation_id VARCHAR(128) NULL,
                    author VARCHAR(128) NULL,
                    final_response BOOLEAN NULL,
                    partial BOOLEAN NULL,
                    compaction_event BOOLEAN NULL,
                    snipped BOOLEAN NOT NULL DEFAULT FALSE,
                    snipped_at DATETIME NULL,
                    snip_event_id VARCHAR(128) NULL,
                    snip_reason TEXT NULL,
                    event_ts BIGINT NULL,
                    content_text TEXT NULL,
                    event_json JSON NOT NULL,
                    created_at DATETIME NOT NULL,
                    UNIQUE KEY uk_session_events_event_id (app_name, user_id, session_id, event_id),
                    KEY idx_session_events_session (app_name, user_id, session_id, id),
                    KEY idx_session_events_invocation (invocation_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

        addColumnIfMissing("session_events", "snipped",
                "ALTER TABLE session_events ADD COLUMN snipped BOOLEAN NOT NULL DEFAULT FALSE AFTER compaction_event");
        addColumnIfMissing("session_events", "snipped_at",
                "ALTER TABLE session_events ADD COLUMN snipped_at DATETIME NULL AFTER snipped");
        addColumnIfMissing("session_events", "snip_event_id",
                "ALTER TABLE session_events ADD COLUMN snip_event_id VARCHAR(128) NULL AFTER snipped_at");
        addColumnIfMissing("session_events", "snip_reason",
                "ALTER TABLE session_events ADD COLUMN snip_reason TEXT NULL AFTER snip_event_id");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS state_values (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    app_name VARCHAR(128) NOT NULL,
                    user_id VARCHAR(128) NULL,
                    scope VARCHAR(32) NOT NULL,
                    state_key VARCHAR(255) NOT NULL,
                    state_value_json JSON NOT NULL,
                    created_at DATETIME NOT NULL,
                    updated_at DATETIME NOT NULL,
                    UNIQUE KEY uk_state_values_identity (app_name, user_id, scope, state_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS memories (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    app_name VARCHAR(128) NOT NULL,
                    user_id VARCHAR(128) NOT NULL,
                    source_session_id VARCHAR(128) NULL,
                    source_event_id VARCHAR(128) NULL,
                    author VARCHAR(128) NULL,
                    content_text TEXT NOT NULL,
                    content_json JSON NOT NULL,
                    content_hash CHAR(64) NOT NULL,
                    created_at DATETIME NOT NULL,
                    UNIQUE KEY uk_memories_hash (app_name, user_id, content_hash),
                    KEY idx_memories_user (app_name, user_id, id),
                    FULLTEXT KEY ft_memories_content (content_text)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    app_name VARCHAR(128) NOT NULL,
                    user_id VARCHAR(128) NOT NULL,
                    session_id VARCHAR(128) NOT NULL,
                    title VARCHAR(255) NULL,
                    created_at DATETIME NOT NULL,
                    updated_at DATETIME NOT NULL,
                    UNIQUE KEY uk_conversations_session (app_name, user_id, session_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    conversation_id BIGINT NOT NULL,
                    app_name VARCHAR(128) NOT NULL,
                    user_id VARCHAR(128) NOT NULL,
                    session_id VARCHAR(128) NOT NULL,
                    role VARCHAR(32) NOT NULL,
                    content_text MEDIUMTEXT NOT NULL,
                    content_json JSON NULL,
                    created_at DATETIME NOT NULL,
                    KEY idx_messages_conversation (conversation_id, id),
                    KEY idx_messages_session (app_name, user_id, session_id, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    private void addColumnIfMissing(String tableName, String columnName, String ddl) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, Integer.class, tableName, columnName);
        if (count == null || count == 0) {
            jdbcTemplate.execute(ddl);
        }
    }
}
