package com.interview.prep.dao;

import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SettingsDao {

    public static final String AI_BASE_URL = "ai_base_url";
    public static final String AI_API_KEY = "ai_api_key";
    public static final String AI_MODEL = "ai_model";
    public static final String AI_RATE_MS = "ai_rate_ms";
    public static final String AI_PROXY = "ai_proxy";

    private final JdbcTemplate jdbc;

    public SettingsDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> all() {
        Map<String, String> map = new HashMap<>();
        for (var row : jdbc.queryForList("SELECT key, value FROM settings")) {
            Object k = row.get("key");
            if (k != null) {
                Object v = row.get("value");
                map.put(k.toString(), v == null ? null : v.toString());
            }
        }
        return map;
    }

    public String get(String key) {
        var list = jdbc.queryForList("SELECT value FROM settings WHERE key = ?", String.class, key);
        return list.isEmpty() ? null : list.get(0);
    }

    public void put(String key, String value) {
        jdbc.update("""
                INSERT INTO settings(key, value) VALUES(?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """, key, value);
    }

    public void delete(String key) {
        jdbc.update("DELETE FROM settings WHERE key = ?", key);
    }

    public int getInt(String key, int defaultValue) {
        String v = get(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
