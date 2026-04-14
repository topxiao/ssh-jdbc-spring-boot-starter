package com.github.topxiao.sshjdbc.context;

import com.github.topxiao.sshjdbc.jdbc.SshJdbcTemplate;
import com.github.topxiao.sshjdbc.provider.ConnectionInfo;
import com.github.topxiao.sshjdbc.provider.ConnectionInfoProvider;
import com.github.topxiao.sshjdbc.tunnel.SshTunnelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SshJdbcRegistryTest {

    private SshJdbcRegistry registry;
    private SshJdbcTemplate primaryTemplate;
    private SshJdbcTemplate secondaryTemplate;
    private SshTunnelService tunnelService;
    private ConnectionInfoProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        tunnelService = mock(SshTunnelService.class);
        when(tunnelService.createOrGetTunnel(anyString(), anyInt())).thenReturn(15432);
        provider = mock(ConnectionInfoProvider.class);

        registry = new SshJdbcRegistry(tunnelService, null, provider);
        primaryTemplate = new SshJdbcTemplate(mock(NamedParameterJdbcTemplate.class));
        secondaryTemplate = new SshJdbcTemplate(mock(NamedParameterJdbcTemplate.class));
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    // ---- Existing tests (adapted for new constructor) ----

    @Test
    void shouldRegisterAndGetTemplate() {
        registry.register("primary", primaryTemplate);
        assertEquals(primaryTemplate, registry.getTemplate("primary"));
    }

    @Test
    void shouldThrowWhenTemplateNotFound() {
        assertThrows(IllegalArgumentException.class, () -> registry.getTemplate("nonexistent"));
    }

    @Test
    void shouldReturnAllDatasourceNames() {
        registry.register("primary", primaryTemplate);
        registry.register("secondary", secondaryTemplate);
        assertEquals(Set.of("primary", "secondary"), registry.getDatasourceNames());
    }

    @Test
    void shouldReturnUnmodifiableTemplates() {
        registry.register("primary", primaryTemplate);
        Map<String, SshJdbcTemplate> templates = registry.getTemplates();
        assertThrows(UnsupportedOperationException.class,
            () -> templates.put("new", secondaryTemplate));
    }

    // ---- New: register(ConnectionInfo) ----

    @Test
    void shouldRegisterByConnectionInfo() throws Exception {
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "postgres", "secret");
        registry.register("dynamic1", info);

        assertTrue(registry.getDatasourceNames().contains("dynamic1"));
        SshJdbcTemplate template = registry.getTemplate("dynamic1");
        assertNotNull(template);
        verify(tunnelService).createOrGetTunnel("10.0.1.100", 5432);
    }

    @Test
    void shouldReplaceWhenRegisteringSameName() throws Exception {
        ConnectionInfo info1 = new ConnectionInfo("10.0.1.100", 5432, "db1", "user", "pass");
        ConnectionInfo info2 = new ConnectionInfo("10.0.2.200", 5432, "db2", "user", "pass");

        registry.register("ds1", info1);
        registry.register("ds1", info2);

        SshJdbcTemplate template = registry.getTemplate("ds1");
        assertNotNull(template);
        verify(tunnelService, times(2)).createOrGetTunnel(anyString(), anyInt());
    }

    @Test
    void shouldThrowWhenRegisterByConnectionInfoWithoutTunnelService() {
        SshJdbcRegistry basic = new SshJdbcRegistry();
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");

        assertThrows(IllegalStateException.class, () -> basic.register("ds1", info));
    }

    // ---- New: unregister ----

    @Test
    void shouldUnregisterByName() {
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");
        registry.register("ds1", info);

        registry.unregister("ds1");

        assertFalse(registry.getDatasourceNames().contains("ds1"));
        assertThrows(IllegalArgumentException.class, () -> registry.getTemplate("ds1"));
    }

    @Test
    void shouldThrowWhenUnregisterNonExistent() {
        assertThrows(IllegalArgumentException.class, () -> registry.unregister("nonexistent"));
    }

    // ---- New: getOrCreate ----

    @Test
    void shouldGetOrCreateNewTemplate() throws Exception {
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");

        SshJdbcTemplate template = registry.getOrCreate(info);

        assertNotNull(template);
        verify(tunnelService).createOrGetTunnel("10.0.1.100", 5432);
    }

    @Test
    void shouldReturnCachedTemplateOnSecondCall() throws Exception {
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");

        SshJdbcTemplate first = registry.getOrCreate(info);
        SshJdbcTemplate second = registry.getOrCreate(info);

        assertSame(first, second);
        verify(tunnelService, times(1)).createOrGetTunnel("10.0.1.100", 5432);
    }

    @Test
    void shouldReturnSameTemplateForRegisteredName() {
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");
        registry.register("ds1", info);

        SshJdbcTemplate fromCache = registry.getOrCreate(info);

        assertSame(registry.getTemplate("ds1"), fromCache);
    }

    @Test
    void shouldThrowWhenGetOrCreateWithoutTunnelService() {
        SshJdbcRegistry basic = new SshJdbcRegistry();
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");

        assertThrows(IllegalStateException.class, () -> basic.getOrCreate(info));
    }

    // ---- New: refresh ----

    @Test
    void shouldRefreshFromProvider() {
        ConnectionInfo info1 = new ConnectionInfo("10.0.1.100", 5432, "db1", "user", "pass");
        when(provider.provide()).thenReturn(Map.of("ds1", info1));

        registry.refresh();

        assertTrue(registry.getDatasourceNames().contains("ds1"));
        verify(provider).provide();
    }

    @Test
    void shouldAddNewOnRefresh() {
        ConnectionInfo info1 = new ConnectionInfo("10.0.1.100", 5432, "db1", "user", "pass");
        ConnectionInfo info2 = new ConnectionInfo("10.0.2.200", 5432, "db2", "user", "pass");

        when(provider.provide()).thenReturn(Map.of("ds1", info1));
        registry.refresh();

        when(provider.provide()).thenReturn(Map.of("ds1", info1, "ds2", info2));
        registry.refresh();

        assertTrue(registry.getDatasourceNames().contains("ds2"));
    }

    @Test
    void shouldRemoveStaleOnRefresh() {
        ConnectionInfo info1 = new ConnectionInfo("10.0.1.100", 5432, "db1", "user", "pass");

        when(provider.provide()).thenReturn(Map.of("ds1", info1));
        registry.refresh();

        when(provider.provide()).thenReturn(Map.of());
        registry.refresh();

        assertFalse(registry.getDatasourceNames().contains("ds1"));
    }

    @Test
    void shouldUpdateChangedOnRefresh() {
        ConnectionInfo info1 = new ConnectionInfo("10.0.1.100", 5432, "db1", "user", "pass");
        ConnectionInfo info1Updated = new ConnectionInfo("10.0.1.100", 5432, "db1_v2", "user", "pass");

        when(provider.provide()).thenReturn(Map.of("ds1", info1));
        registry.refresh();

        when(provider.provide()).thenReturn(Map.of("ds1", info1Updated));
        registry.refresh();

        assertTrue(registry.getDatasourceNames().contains("ds1"));
    }

    @Test
    void shouldNotAffectManuallyRegisteredOnRefresh() {
        registry.register("manual", primaryTemplate);

        when(provider.provide()).thenReturn(Map.of());
        registry.refresh();

        assertTrue(registry.getDatasourceNames().contains("manual"));
    }

    @Test
    void shouldThrowWhenRefreshWithoutProvider() {
        SshJdbcRegistry basic = new SshJdbcRegistry();
        assertThrows(IllegalStateException.class, basic::refresh);
    }

    // ---- shutdown ----

    @Test
    void shouldShutdownCleanly() {
        registry.register("primary", primaryTemplate);
        registry.register("secondary", secondaryTemplate);
        assertDoesNotThrow(() -> registry.shutdown());
    }
}
