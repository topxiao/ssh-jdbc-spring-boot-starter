package com.github.topxiao.sshjdbc.provider;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionInfoTest {

    @Test
    void shouldCreateRecordWithAllFields() {
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "postgres", "secret");
        assertEquals("10.0.1.100", info.host());
        assertEquals(5432, info.port());
        assertEquals("mydb", info.database());
        assertEquals("postgres", info.username());
        assertEquals("secret", info.password());
    }

    @Test
    void shouldGenerateCacheKey() {
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "postgres", "secret");
        assertEquals("10.0.1.100:5432:mydb:postgres", info.cacheKey());
    }

    @Test
    void shouldBuildJdbcUrl() {
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "postgres", "secret");
        assertEquals("jdbc:postgresql://10.0.1.100:5432/mydb", info.jdbcUrl());
    }

    @Test
    void shouldBuildJdbcUrlWithLocalPort() {
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "postgres", "secret");
        assertEquals("jdbc:postgresql://localhost:12345/mydb", info.jdbcUrlWithLocalPort(12345));
    }
}
