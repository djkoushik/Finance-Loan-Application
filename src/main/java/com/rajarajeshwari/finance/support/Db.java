package com.rajarajeshwari.finance.support;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thin JDBC helper: named parameters in, rows out as maps with camelCase keys (debtor_code -> debtorCode). */
@Component
public class Db {

    private static final RowMapper<Map<String, Object>> CAMEL_ROWS = (rs, rowNum) -> {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            Object v = rs.getObject(i);
            if (v instanceof java.sql.Timestamp) {
                v = ((java.sql.Timestamp) v).toLocalDateTime();
            }
            row.put(camel(md.getColumnLabel(i)), v);
        }
        return row;
    };

    private final NamedParameterJdbcTemplate jdbc;

    public Db(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(String sql, Map<String, ?> params) {
        return jdbc.query(sql, params, CAMEL_ROWS);
    }

    public List<Map<String, Object>> list(String sql) {
        return list(sql, Collections.<String, Object>emptyMap());
    }

    /** First row or null. */
    public Map<String, Object> one(String sql, Map<String, ?> params) {
        List<Map<String, Object>> rows = list(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int update(String sql, Map<String, ?> params) {
        return jdbc.update(sql, params);
    }

    public <T> T scalar(String sql, Map<String, ?> params, Class<T> type) {
        List<T> r = jdbc.queryForList(sql, params, type);
        return r.isEmpty() ? null : r.get(0);
    }

    public long count(String sql, Map<String, ?> params) {
        Long v = scalar(sql, params, Long.class);
        return v == null ? 0 : v;
    }

    /** Serialises writers that compute "max(id)+1" style numbers. Must be called inside a transaction. */
    public void lockNumbering() {
        jdbc.getJdbcOperations().execute("SELECT pg_advisory_xact_lock(4711)");
    }

    public static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    public static List<Map<String, Object>> emptyList() {
        return new ArrayList<Map<String, Object>>();
    }
}
