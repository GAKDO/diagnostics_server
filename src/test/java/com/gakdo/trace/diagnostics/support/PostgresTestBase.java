// 테스트 도구(테스트 자체는 아님): 진짜 PostgreSQL 데이터베이스가 필요한 통합 테스트의 바탕.
// GitHub Actions에서는 Docker로 DB를 띄우고, Docker가 없는 곳에서는 로컬 테스트 DB에 붙거나 테스트를 건너뛴다.

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
// (도구) @SpringBootTest = 서버 전체를 띄우는 통합 테스트. @AutoConfigureMockMvc = 그 상태에서 MockMvc(가짜 HTTP 요청 도구)도 쓸 수 있게 한다
@SpringBootTest
@AutoConfigureMockMvc
public abstract class PostgresTestBase {

    // 환경 변수 TRACE_IT_LOCAL이 1이면 로컬 테스트 DB를 쓴다는 표시
    private static final boolean LOCAL = "1".equals(System.getenv("TRACE_IT_LOCAL"));
    // (도구) Testcontainers = 테스트 동안 Docker로 진짜 DB를 잠깐 띄우는 도구
    // Docker로 띄울 PostgreSQL 그릇(처음엔 비어 있음)
    private static PostgreSQLContainer<?> postgres;

    public static boolean infrastructureAvailable() {
        // 로컬 DB를 쓰거나 Docker가 있으면 통합 테스트를 돌릴 수 있다고 답한다
        return LOCAL || dockerAvailable();
    }

    static boolean dockerAvailable() {
        try {
            // Docker를 쓸 수 있는지 물어본다
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            // 물어보다 오류가 나면 "없음"으로 본다 = 테스트를 건너뛰게
            return false;
        }
    }

    // (도구) @DynamicPropertySource = 서버가 뜨기 전에 DB 주소 같은 설정 값을 코드로 넣는다
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        // 로컬 모드면 로컬 PostgreSQL 주소를 넣는다
        if (LOCAL) {
            // DB 주소: 로컬 테스트 전용 DB trace_it
            registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/trace_it");
            // DB 사용자 이름
            registry.add("spring.datasource.username", () -> "postgres");
            // 로컬 테스트 DB는 비밀번호 없음
            registry.add("spring.datasource.password", () -> "");
            // 로컬 설정을 마쳤으니 컨테이너는 띄우지 않고 끝낸다
            return;
        }
        // Docker로 PostgreSQL을 띄운다(이미 떴으면 그대로 쓴다)
        startContainer();
        // 띄운 DB 주소를 넣는다
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        // 컨테이너가 정한 사용자 이름을 넣는다
        registry.add("spring.datasource.username", postgres::getUsername);
        // 컨테이너가 정한 비밀번호를 넣는다(시험용 임시 값)
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static synchronized void startContainer() {
        // 아직 안 띄웠을 때만 띄운다 = 여러 테스트 클래스가 컨테이너 하나를 같이 쓴다
        if (postgres == null) {
            // PostgreSQL 16 컨테이너를 trace DB 이름으로 준비한다
            postgres = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("trace");
            // DB를 띄운다
            postgres.start();
        }
    }
}
