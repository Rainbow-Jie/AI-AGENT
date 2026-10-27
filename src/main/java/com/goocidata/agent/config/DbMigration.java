package com.goocidata.agent.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量库结构迁移：老库补列，避免升级时要求删库重建。
 * <p>
 * schema.sql 里的 CREATE TABLE IF NOT EXISTS 对已存在的表不会补列，
 * 所以新增字段统一在这里登记一次（幂等，重复执行无副作用）。
 *
 * @author nzj
 */
@Slf4j
@Order(1)
@Component
@RequiredArgsConstructor
public class DbMigration implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        // 智能体的工具权限字段
        addColumn("agent_info", "tool_mode", "TEXT DEFAULT 'off'");
        addColumn("agent_info", "work_dir", "TEXT");
        addColumn("agent_info", "allow_cmds", "TEXT");
        addColumn("agent_info", "deny_cmds", "TEXT");
        addColumn("agent_info", "tool_timeout", "INTEGER DEFAULT 30");
        addColumn("agent_info", "max_steps", "INTEGER DEFAULT 6");
        // 消息的工具调用记录
        addColumn("chat_message", "tool_log", "TEXT");
        // 消息的 token 用量统计
        addColumn("chat_message", "usage", "TEXT");
        // 用户头像（个人中心）
        addColumn("sys_user", "avatar", "TEXT");
    }

    private void addColumn(String table, String column, String ddl) {
        Set<String> columns = columnsOf(table);
        if (columns.isEmpty()) {
            // 表还不存在（schema.sql 尚未建表），交给建表脚本，不算异常
            return;
        }
        if (columns.contains(column.toLowerCase())) {
            return;
        }
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl);
            log.info("库结构升级：{}.{} 已新增", table, column);
        } catch (Exception e) {
            log.warn("库结构升级失败 {}.{}：{}", table, column, e.getMessage());
        }
    }

    private Set<String> columnsOf(String table) {
        Set<String> result = new HashSet<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("PRAGMA table_info(" + table + ")");
            for (Map<String, Object> row : rows) {
                Object name = row.get("name");
                if (name != null) {
                    result.add(String.valueOf(name).toLowerCase());
                }
            }
        } catch (Exception e) {
            log.debug("读取表结构失败 {}：{}", table, e.getMessage());
        }
        return result;
    }
}
