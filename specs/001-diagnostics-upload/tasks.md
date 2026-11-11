# 작업: DI1 진단 수신·저장

- [x] T001 pom: data-jpa·amqp·oauth2-resource-server·flyway·postgresql·json-schema-validator·testcontainers 의존성
- [x] T002 application.yml: datasource·flyway(diagnostics)·rabbitmq·JWKS·jackson(환경 변수 이름은 gitops infra.env·trace-secrets와 같게)
- [x] T003 Flyway V1(docs V1 diagnostics 절) + V2(data-model.md)
- [x] T004 contracts 사본(result.schema.json·codes.gen.schema.json) + scripts/sync-contracts.sh(--check)
- [x] T005 common: ErrorCode(계약 이름 대조 테스트)·DomainException·ProblemAdvice·ProblemFactory·SecurityConfig·Caller·PublicIds·Times
- [x] T006 DiagnosticSchemaValidator + 계약 예시 7개 시험 (SC-001)
- [x] T007 DiagnosticUpload record + UploadRules(단위·median·지표 중복) 단위 시험
- [x] T008 엔티티·저장소(ddl-auto validate)
- [x] T009 DiagnosticRunService: 저장·멱등·409·시리얼 대조·baseRun·이벤트
- [x] T010 DiagnosticRunController POST /runs, GET /checkpoints/{id} + RunSummaryAssembler
- [x] T011 WebMvc 시험: 이야기 1·2·4 오류 경로(401·403·400·422)
- [x] T012 통합 시험(PostgreSQL, 로컬 또는 Testcontainers): 저장·재생·409·시리얼·조회·previous
- [x] T013 통합 시험(Testcontainers RabbitMQ): 이벤트 라우팅 키·message_id·스키마 형식
- [x] T014 README·CHANGELOG, `./mvnw verify`
