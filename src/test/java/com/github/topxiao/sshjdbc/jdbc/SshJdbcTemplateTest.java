package com.github.topxiao.sshjdbc.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SshJdbcTemplateTest {

    private NamedParameterJdbcTemplate namedTemplate;
    private JdbcTemplate jdbcTemplate;
    private SshJdbcTemplate sshJdbc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        namedTemplate = mock(NamedParameterJdbcTemplate.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        sshJdbc = new SshJdbcTemplate(namedTemplate);
    }

    @Test
    void shouldDelegateQueryForListWithParams() {
        List<Map<String, Object>> expected = List.of(Map.of("id", 1));
        when(namedTemplate.queryForList(eq("SELECT * FROM t WHERE id = :id"), anyMap()))
            .thenReturn(expected);
        List<Map<String, Object>> result = sshJdbc.queryForList(
            "SELECT * FROM t WHERE id = :id", Map.of("id", 1));
        assertEquals(expected, result);
    }

    @Test
    void shouldDelegateQueryForListWithoutParams() {
        when(jdbcTemplate.queryForList("SELECT * FROM t")).thenReturn(List.of(Map.of("id", 1)));
        when(namedTemplate.getJdbcTemplate()).thenReturn(jdbcTemplate);
        List<Map<String, Object>> result = sshJdbc.queryForList("SELECT * FROM t");
        assertEquals(1, result.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDelegateQueryWithRowMapper() {
        RowMapper<String> rowMapper = (rs, rowNum) -> rs.getString("name");
        when(namedTemplate.query(eq("SELECT name FROM t"), anyMap(), any(RowMapper.class)))
            .thenReturn(List.of("alice", "bob"));
        List<String> result = sshJdbc.query("SELECT name FROM t", Map.of(), rowMapper);
        assertEquals(List.of("alice", "bob"), result);
    }

    @Test
    void shouldDelegateQueryForMap() {
        Map<String, Object> expected = Map.of("id", 1, "name", "alice");
        when(namedTemplate.queryForMap(eq("SELECT * FROM t WHERE id = :id"), anyMap()))
            .thenReturn(expected);
        Map<String, Object> result = sshJdbc.queryForMap(
            "SELECT * FROM t WHERE id = :id", Map.of("id", 1));
        assertEquals(expected, result);
    }

    @Test
    void shouldDelegateQueryForObject() {
        when(namedTemplate.queryForObject(eq("SELECT COUNT(*) FROM t"), anyMap(), eq(Integer.class)))
            .thenReturn(42);
        Integer result = sshJdbc.queryForObject("SELECT COUNT(*) FROM t", Map.of(), Integer.class);
        assertEquals(42, result);
    }

    @Test
    void shouldDelegateUpdateWithParams() {
        when(namedTemplate.update(eq("UPDATE t SET name = :name WHERE id = :id"), anyMap()))
            .thenReturn(1);
        int affected = sshJdbc.update(
            "UPDATE t SET name = :name WHERE id = :id", Map.of("name", "alice", "id", 1));
        assertEquals(1, affected);
    }

    @Test
    void shouldDelegateUpdateWithoutParams() {
        when(jdbcTemplate.update("DELETE FROM t")).thenReturn(5);
        when(namedTemplate.getJdbcTemplate()).thenReturn(jdbcTemplate);
        int affected = sshJdbc.update("DELETE FROM t");
        assertEquals(5, affected);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDelegateBatchUpdate() {
        Map<String, ?> batch1 = Map.of("id", 1, "name", "a");
        Map<String, ?> batch2 = Map.of("id", 2, "name", "b");
        when(namedTemplate.batchUpdate(eq("INSERT INTO t (id, name) VALUES (:id, :name)"), any(Map[].class)))
            .thenReturn(new int[]{1, 1});
        int[] result = sshJdbc.batchUpdate(
            "INSERT INTO t (id, name) VALUES (:id, :name)", batch1, batch2);
        assertArrayEquals(new int[]{1, 1}, result);
    }

    @Test
    void shouldDelegateExecute() {
        when(namedTemplate.getJdbcTemplate()).thenReturn(jdbcTemplate);
        sshJdbc.execute("CREATE TABLE test (id INT)");
        verify(jdbcTemplate).execute("CREATE TABLE test (id INT)");
    }

    @Test
    void shouldExposeNamedParameterJdbcTemplate() {
        assertSame(namedTemplate, sshJdbc.getNamedParameterJdbcTemplate());
    }

    @Test
    void shouldExposeJdbcTemplate() {
        when(namedTemplate.getJdbcTemplate()).thenReturn(jdbcTemplate);
        assertSame(jdbcTemplate, sshJdbc.getJdbcTemplate());
    }
}
