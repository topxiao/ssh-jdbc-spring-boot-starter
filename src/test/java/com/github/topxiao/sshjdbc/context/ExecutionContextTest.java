package com.github.topxiao.sshjdbc.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionContextTest {

    @AfterEach
    void tearDown() {
        ExecutionContext.clear();
    }

    @Test
    void shouldSetAndGetContext() {
        ExecutionContext ctx = ExecutionContext.builder()
                .corpCode("midea")
                .apply();
        assertEquals("midea", ExecutionContext.current().getCorpCode());
        assertSame(ctx, ExecutionContext.current());
    }

    @Test
    void shouldClearContext() {
        ExecutionContext.builder().corpCode("midea").apply();
        ExecutionContext.clear();
        assertNull(ExecutionContext.current());
    }

    @Test
    void shouldReturnNullWhenNoContextSet() {
        assertNull(ExecutionContext.current());
    }

    @Test
    void shouldStoreAndRetrieveAttributes() {
        ExecutionContext.builder()
                .corpCode("midea")
                .put("env", "v4")
                .apply();
        assertEquals("v4", ExecutionContext.current().getAttribute("env"));
        assertNull(ExecutionContext.current().getAttribute("nonexistent"));
    }

    @Test
    void shouldDetectFullConnectionInfo() {
        ExecutionContext ctx = ExecutionContext.builder()
                .dbHost("10.0.1.100")
                .dbPort(5432)
                .dbDatabase("mydb")
                .dbUser("postgres")
                .dbPassword("secret")
                .build();
        assertTrue(ctx.hasFullConnectionInfo());
    }

    @Test
    void shouldNotHaveFullConnectionInfoWhenPartial() {
        ExecutionContext ctx = ExecutionContext.builder()
                .corpCode("midea")
                .put("env", "v4")
                .build();
        assertFalse(ctx.hasFullConnectionInfo());
    }

    @Test
    void shouldNotHaveFullConnectionInfoWhenMissingDbHost() {
        ExecutionContext ctx = ExecutionContext.builder()
                .dbPort(5432)
                .dbDatabase("mydb")
                .dbUser("postgres")
                .dbPassword("secret")
                .build();
        assertFalse(ctx.hasFullConnectionInfo());
    }

    @Test
    void shouldBuildWithAllDbParams() {
        ExecutionContext ctx = ExecutionContext.builder()
                .corpCode("midea")
                .put("env", "v4")
                .dbHost("10.0.1.100")
                .dbPort(5432)
                .dbDatabase("mydb")
                .dbUser("postgres")
                .dbPassword("secret")
                .apply();
        assertEquals("10.0.1.100", ctx.getDbHost());
        assertEquals(5432, ctx.getDbPort());
        assertEquals("mydb", ctx.getDbDatabase());
        assertEquals("postgres", ctx.getDbUser());
        assertEquals("secret", ctx.getDbPassword());
    }
}
