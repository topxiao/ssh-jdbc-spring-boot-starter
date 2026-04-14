package com.github.topxiao.sshjdbc.context;

import com.github.topxiao.sshjdbc.jdbc.SshJdbcTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry that indexes {@link SshJdbcTemplate} instances by datasource name,
 * enabling lookup of the correct template for a given datasource at runtime.
 */
public class SshJdbcRegistry {

    private final Map<String, SshJdbcTemplate> templates = new LinkedHashMap<>();

    public void register(String name, SshJdbcTemplate template) {
        templates.put(name, template);
    }

    public SshJdbcTemplate getTemplate(String datasourceName) {
        SshJdbcTemplate template = templates.get(datasourceName);
        if (template == null) {
            throw new IllegalArgumentException(
                "未找到数据源: " + datasourceName
                + "，可用数据源: " + templates.keySet());
        }
        return template;
    }

    public Set<String> getDatasourceNames() {
        return Collections.unmodifiableSet(templates.keySet());
    }

    public Map<String, SshJdbcTemplate> getTemplates() {
        return Collections.unmodifiableMap(templates);
    }
}
