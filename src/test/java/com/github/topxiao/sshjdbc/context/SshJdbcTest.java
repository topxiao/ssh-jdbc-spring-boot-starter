package com.github.topxiao.sshjdbc.context;

import com.github.topxiao.sshjdbc.jdbc.SshJdbcTemplate;
import com.github.topxiao.sshjdbc.provider.ConnectionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SshJdbcTest {

    private SshJdbcRegistry registry;
    private SshJdbcTemplate mockTemplate;
    private NamedParameterJdbcTemplate namedTemplate;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        registry = mock(SshJdbcRegistry.class);
        namedTemplate = mock(NamedParameterJdbcTemplate.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        mockTemplate = new SshJdbcTemplate(namedTemplate);
        when(namedTemplate.getJdbcTemplate()).thenReturn(jdbcTemplate);

        SshJdbc.init(registry, List.of());
    }

    @AfterEach
    void tearDown() {
        ExecutionContext.clear();
        SshJdbc.reset();
    }

    // ---- Resolution: full connection info ----

    @Test
    void shouldResolveFromFullConnectionInfo() {
        ExecutionContext.builder()
                .dbHost("10.0.1.100").dbPort(5432)
                .dbDatabase("mydb").dbUser("postgres").dbPassword("secret")
                .apply();

        when(registry.getOrCreate(any(ConnectionInfo.class))).thenReturn(mockTemplate);

        SshJdbcTemplate result = SshJdbc.resolveTemplate();
        assertNotNull(result);
        verify(registry).getOrCreate(any(ConnectionInfo.class));
    }

    // ---- Resolution: via resolver ----

    @Test
    void shouldResolveViaResolver() {
        ExecutionContext.builder().corpCode("midea").put("env", "v4").apply();

        ConnectionInfo expectedInfo = new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");
        ConnectionInfoResolver resolver = ctx -> {
            if ("midea".equals(ctx.getCorpCode())) return expectedInfo;
            return null;
        };
        SshJdbc.init(registry, List.of(resolver));

        when(registry.getOrCreate(expectedInfo)).thenReturn(mockTemplate);

        SshJdbcTemplate result = SshJdbc.resolveTemplate();
        assertNotNull(result);
        verify(registry).getOrCreate(expectedInfo);
    }

    @Test
    void shouldTryMultipleResolversInOrder() {
        ExecutionContext.builder().corpCode("acme").apply();

        ConnectionInfoResolver resolver1 = ctx -> null; // can't handle
        ConnectionInfoResolver resolver2 = ctx ->
            new ConnectionInfo("10.0.2.200", 5432, "db2", "user", "pass");

        SshJdbc.init(registry, List.of(resolver1, resolver2));
        when(registry.getOrCreate(any(ConnectionInfo.class))).thenReturn(mockTemplate);

        SshJdbc.resolveTemplate();
        verify(registry).getOrCreate(argThat(info ->
            "10.0.2.200".equals(info.host())));
    }

    @Test
    void shouldThrowWhenNoContextSet() {
        assertThrows(IllegalStateException.class, SshJdbc::resolveTemplate);
    }

    @Test
    void shouldThrowWhenCannotResolve() {
        ExecutionContext.builder().corpCode("unknown").apply();
        SshJdbc.init(registry, List.of()); // no resolvers

        assertThrows(IllegalStateException.class, SshJdbc::resolveTemplate);
    }

    // ---- Static query delegation ----

    @Test
    void shouldDelegateQueryForList() {
        setupResolvedTemplate();
        when(namedTemplate.queryForList(eq("SELECT * FROM t"), anyMap()))
            .thenReturn(List.of(Map.of("id", 1)));

        List<Map<String, Object>> result = SshJdbc.queryForList(
            "SELECT * FROM t", Map.of());
        assertEquals(1, result.size());
    }

    @Test
    void shouldDelegateQueryForMap() {
        setupResolvedTemplate();
        Map<String, Object> expected = Map.of("id", 1, "name", "alice");
        when(namedTemplate.queryForMap(eq("SELECT * FROM t WHERE id=:id"), anyMap()))
            .thenReturn(expected);

        Map<String, Object> result = SshJdbc.queryForMap(
            "SELECT * FROM t WHERE id=:id", Map.of("id", 1));
        assertEquals(expected, result);
    }

    @Test
    void shouldDelegateQueryForObject() {
        setupResolvedTemplate();
        when(namedTemplate.queryForObject(eq("SELECT COUNT(*) FROM t"), anyMap(), eq(Integer.class)))
            .thenReturn(42);

        Integer result = SshJdbc.queryForObject("SELECT COUNT(*) FROM t", Map.of(), Integer.class);
        assertEquals(42, result);
    }

    @Test
    void shouldDelegateUpdate() {
        setupResolvedTemplate();
        when(namedTemplate.update(eq("UPDATE t SET name=:name"), anyMap()))
            .thenReturn(1);

        int result = SshJdbc.update("UPDATE t SET name=:name", Map.of("name", "alice"));
        assertEquals(1, result);
    }

    @Test
    void shouldDelegateExecute() {
        setupResolvedTemplate();
        SshJdbc.execute("CREATE TABLE test (id INT)");
        verify(jdbcTemplate).execute("CREATE TABLE test (id INT)");
    }

    @Test
    void shouldDelegateGetTemplateByName() {
        when(registry.getTemplate("primary")).thenReturn(mockTemplate);
        SshJdbcTemplate result = SshJdbc.getTemplate("primary");
        assertSame(mockTemplate, result);
    }

    // ---- Helper ----

    private void setupResolvedTemplate() {
        ExecutionContext.builder()
                .dbHost("10.0.1.100").dbPort(5432)
                .dbDatabase("mydb").dbUser("postgres").dbPassword("secret")
                .apply();
        when(registry.getOrCreate(any(ConnectionInfo.class))).thenReturn(mockTemplate);
    }
}
