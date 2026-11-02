# 구현 계획: DI1 진단 수신·저장

**명세**: [spec.md](spec.md) · **데이터**: [data-model.md](data-model.md) · **작업**: [tasks.md](tasks.md)

## 기술 맥락

Java 21, Spring Boot 3.5.7, Maven(./mvnw). Spring Web MVC, Spring Data JPA, PostgreSQL 스키마 diagnostics, Flyway(V1 = GAKDO/docs db/V1__init.sql diagnostics 절 그대로, V2 = DI1 보강), Spring Security OAuth2 리소스 서버(auth-server JWKS, iss=trace), 진단 JSON 검증은 contracts JSON Schema(networknt json-schema-validator 1.5, draft 2020-12), Spring AMQP(trace.events, 채널 트랜잭션), Bean Validation, ProblemDetail(RFC 9457). 테스트는 JUnit 5 + Spring Boot Test(MockMvc) + Testcontainers(PostgreSQL·RabbitMQ, `disabledWithoutDocker = true`), Docker 없는 세션은 `TRACE_IT_LOCAL=1`이면 로컬 PostgreSQL(DB trace_it)로 통합 테스트를 돌린다(auth_server와 같은 방식). InfluxDB 추이는 DI2에서.

## 헌법 확인

| 원칙 | 확인 |
| --- | --- |
| I 계약이 먼저 | 경로·응답·오류 code·이벤트를 contracts에서 그대로. 스키마는 사본을 두고 sync 스크립트로 맞춤. 백로그와 다른 곳(422·409)은 계약을 따름 |
| II 서비스 경계 | diagnostics 스키마만 씀. 렌탈 기기 시리얼은 REST로 묻지 않고 같은 렌탈의 앞선 진단과 대조 |
| III 기록은 고칠 수 없다 | 실행은 덧붙이기만, 원본과 SHA-256 저장, 읽지 못한 값은 null + 사유 |
| IV 테스트 | 수용 기준 문장을 테스트 이름으로, 계약 예시 7개를 그대로 시험, 커버리지 40% 이상 |
| V 비밀·개인정보 | 주소·비밀은 환경 변수, 스키마가 컴퓨터·사용자 이름 키를 거부, 로그에 본문을 남기지 않음 |
| VI 단순하게 | 원본은 DB text(MinIO 안 씀), 플래그 계산은 DI2로 미룸 |

## 구조

```
src/main/java/com/gakdo/trace/diagnostics/
  common/api      ProblemAdvice, ProblemFactory
  common/domain   DomainException, ErrorCode
  common/infra    SecurityConfig, Caller, PublicIds, Times, Hashes
  run/api         DiagnosticRunController, DiagnosticDtos(응답 record)
  run/application DiagnosticRunService, RunSummaryAssembler, DiagnosticsEventPublisher, DiagnosticsRecordedEvent
  run/domain      DiagnosticUpload(본문 record), UploadRules, DiagnosticRun, HardwareComponent, BatteryReading,
                  SsdHealth, BenchmarkResult, InspectionFlag
  run/infra       DiagnosticSchemaValidator, DiagnosticRunRepository, InspectionFlagRepository, AmqpConfig,
                  RabbitDiagnosticsEventPublisher
src/main/resources/contracts/      diagnostics/result.schema.json, schemas/codes.gen.schema.json (contracts 사본)
src/main/resources/db/migration/   V1__init_diagnostics.sql, V2__di1_run_upload.sql
scripts/sync-contracts.sh          contracts 체크아웃에서 사본 갱신·비교(--check)
```

## 흐름

1. 컨트롤러가 본문을 `byte[]`로 받는다 → SHA-256(원본 그대로).
2. Caller(AGENT, scope·rentalId NanoID) 확인 → JSON 파싱(실패 400) → JSON Schema 검증(실패 422, errors[] = instanceLocation·메시지) → record로 변환 → UploadRules(422).
3. 서비스(트랜잭션): 같은 시점 실행이 있으면 키·해시로 재생/409/422 → 같은 렌탈 앞선 실행(baseRun) 조회, 시리얼 대조(422) → 저장 → 이벤트 발행(채널 트랜잭션이라 DB 커밋과 함께).
4. 동시 요청이 유일 제약에 걸리면 트랜잭션 밖에서 한 번 더 조회해 재생/409로 답한다.

## 복잡도 기록
- 스키마 사본: 런타임에 contracts를 받으러 가지 않게(오프라인·CI) 레포 안에 두고, `scripts/sync-contracts.sh --check`로 어긋남을 찾는다.
- 로컬 통합 테스트: Docker가 없는 세션을 위해 `TRACE_IT_LOCAL=1` 경로를 둔다. RabbitMQ까지 확인하는 테스트는 Docker가 있을 때만 돈다.
