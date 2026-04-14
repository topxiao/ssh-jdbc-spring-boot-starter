package com.github.topxiao.sshjdbc.context;

import com.github.topxiao.sshjdbc.jdbc.SshJdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshJdbcRegistryTest {

    private SshJdbcRegistry registry;
    private SshJdbcTemplate primaryTemplate;
    private SshJdbcTemplate secondaryTemplate;

    @BeforeEach
    void setUp() {
        registry = new SshJdbcRegistry();
        primaryTemplate = new SshJdbcTemplate(mock(NamedParameterJdbcTemplate.class));
        secondaryTemplate = new SshJdbcTemplate(mock(NamedParameterJdbcTemplate.class));
    }

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
}
