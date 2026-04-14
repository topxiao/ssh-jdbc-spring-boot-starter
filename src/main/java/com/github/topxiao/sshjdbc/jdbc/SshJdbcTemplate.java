package com.github.topxiao.sshjdbc.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Convenience wrapper around {@link NamedParameterJdbcTemplate} that provides
 * a simplified API for query/update/batch operations via SSH tunnelled connections.
 */
public class SshJdbcTemplate {

    private final NamedParameterJdbcTemplate namedTemplate;

    public SshJdbcTemplate(NamedParameterJdbcTemplate namedTemplate) {
        this.namedTemplate = namedTemplate;
    }

    // ---- Query methods ----

    public List<Map<String, Object>> queryForList(String sql) {
        return namedTemplate.getJdbcTemplate().queryForList(sql);
    }

    public List<Map<String, Object>> queryForList(String sql, Map<String, ?> params) {
        return namedTemplate.queryForList(sql, params);
    }

    public <T> List<T> queryForList(String sql, Map<String, ?> params, Class<T> elementType) {
        return namedTemplate.queryForList(sql, params, elementType);
    }

    public <T> List<T> query(String sql, Map<String, ?> params, RowMapper<T> rowMapper) {
        return namedTemplate.query(sql, params, rowMapper);
    }

    public Map<String, Object> queryForMap(String sql, Map<String, ?> params) {
        return namedTemplate.queryForMap(sql, params);
    }

    public <T> T queryForObject(String sql, Map<String, ?> params, Class<T> requiredType) {
        return namedTemplate.queryForObject(sql, params, requiredType);
    }

    // ---- Update methods ----

    public int update(String sql) {
        return namedTemplate.getJdbcTemplate().update(sql);
    }

    public int update(String sql, Map<String, ?> params) {
        return namedTemplate.update(sql, params);
    }

    @SafeVarargs
    public final int[] batchUpdate(String sql, Map<String, ?>... batchArgs) {
        return namedTemplate.batchUpdate(sql, batchArgs);
    }

    public void execute(String sql) {
        namedTemplate.getJdbcTemplate().execute(sql);
    }

    // ---- Underlying access ----

    public NamedParameterJdbcTemplate getNamedParameterJdbcTemplate() {
        return namedTemplate;
    }

    public JdbcTemplate getJdbcTemplate() {
        return namedTemplate.getJdbcTemplate();
    }
}
