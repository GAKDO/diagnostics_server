package com.gakdo.trace.diagnostics.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 실제 PostgreSQL(Flyway V1·V2, ddl-auto validate)이 필요한 통합 테스트의 바탕.
 * CI(GitHub Actions)는 Testcontainers로 띄우고, Docker가 없는 Claude 세션은 TRACE_IT_LOCAL=1이면
 * 로컬 PostgreSQL의 테스트 전용 DB trace_it에 붙는다(auth_server와 같은 방식, 만들기: {@code createdb -U postgres trace_it}).
 * 둘 다 없으면 건너뛴다(하위 클래스에 @EnabledIf("…PostgresTestBase#infrastructureAvailable")).
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class PostgresTestBase {

    private static final boolean LOCAL = "1".equals(System.getenv("TRACE_IT_LOCAL"));
    private static PostgreSQLContainer<?> postgres;

    public static boolean infrastructureAvailable() {
        return LOCAL || dockerAvailable();
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        if (LOCAL) {
            registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/trace_it");
            registry.add("spring.datasource.username", () -> "postgres");
            registry.add("spring.datasource.password", () -> "");
            return;
        }
        startContainer();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static synchronized void startContainer() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("trace");
            postgres.start();
        }
    }
}
