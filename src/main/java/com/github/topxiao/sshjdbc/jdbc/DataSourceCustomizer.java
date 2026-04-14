package com.github.topxiao.sshjdbc.jdbc;

import org.springframework.boot.jdbc.DataSourceBuilder;
import javax.sql.DataSource;

/**
 * Callback for customising a {@link DataSource} after the starter has applied
 * its defaults (URL, username, password, driver-class-name).
 *
 * <p>Users can register an implementation as a Spring bean; the auto-configuration
 * will discover it and apply it to every SSH-tunneled datasource it creates.
 *
 * <p>This is a {@link FunctionalInterface} so it can be supplied as a lambda.
 */
@FunctionalInterface
public interface DataSourceCustomizer {
    DataSource customize(DataSourceBuilder<?> builder, String datasourceName);
}
