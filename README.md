# diagnostics-server

TRACE(노트북 렌탈 상태 기록 서비스)의 **diagnostics-server**: 진단 검증·전후 비교·점검 플래그·추이 기록

- 담당: A
- 설계: [GAKDO/docs](https://github.com/GAKDO/docs) — 요구사항·아키텍처·ERD·REST API v0
- 기술: Java 21, Spring Boot 3.5.7, Maven

## 실행

```bash
./mvnw spring-boot:run
```

```bash
curl http://localhost:8080/api/v1/diagnostics/ping
```

## API (DI1)

계약: GAKDO/contracts `openapi/diagnostics-server.yaml`, 명세: [specs/001-diagnostics-upload](specs/001-diagnostics-upload/spec.md)

| 경로 | 토큰 | 하는 일 |
|---|---|---|
| `POST /api/v1/diagnostics/runs` | AGENT(페어링) | `diag.v1` 진단 JSON을 검증(422)해 토큰의 시점에 저장, 원본 SHA-256, `trace.diagnostics.recorded` 발행. 시점당 하나(409), `Idempotency-Key` 재생 |
| `GET /api/v1/diagnostics/checkpoints/{checkpointId}` | ADMIN, CUSTOMER(본인 렌탈) | 리포트용 시점 진단 요약(배터리·SSD·부품·벤치마크) + 같은 렌탈 직전 시점 + 점검 플래그 |

진단 JSON Schema는 contracts 사본(`src/main/resources/contracts`)으로 검증합니다. contracts가 바뀌면 `scripts/sync-contracts.sh ../contracts`로 맞추고 `--check`로 확인합니다.

환경 변수: `POSTGRES_HOST`·`POSTGRES_PORT`·`POSTGRES_DB`, `DB_USERNAME`·`DB_PASSWORD`, `RABBITMQ_HOST`·`RABBITMQ_PORT`·`RABBITMQ_VHOST`·`RABBITMQ_USERNAME`·`RABBITMQ_PASSWORD`, `AUTH_JWKS_URI`(또는 `AUTH_SERVER_URL`).

## 테스트

```bash
./mvnw verify
```

Docker가 있으면 Testcontainers(PostgreSQL·RabbitMQ)로 통합 테스트를 돌립니다. Docker가 없는 세션은 로컬 PostgreSQL에 `createdb -U postgres trace_it`를 만들고 `TRACE_IT_LOCAL=1 ./mvnw verify`로 PostgreSQL 통합 테스트를 돌립니다(RabbitMQ 시험은 건너뜀).

줄 커버리지 40% 미만이면 실패합니다(블로그와 같은 기준).

## CI/CD

| 워크플로 | 언제 | 하는 일 |
|---|---|---|
| `backend-ci` | PR, develop push | 테스트 + JaCoCo 40% + (켜져 있으면) 학교 SonarQube |
| `배포` | PR(빌드만), develop·main push | 이미지 → GHCR(`ghcr.io/<조직>/diagnostics-server`). main이면 gitops 레포 이미지 태그 갱신 → Argo CD 배포 |
| `비밀값 검사` | PR, main·develop push | 학교 공용 비밀번호 패턴 + gitleaks |
| `release` | main에 CHANGELOG 버전 | 태그와 GitHub Release |
| `PR 알림`·`CI 결과 알림` | PR·CI 끝 | Discord·텔레그램 (변수로 켬) |

브랜치 규칙과 릴리스 절차는 [CONTRIBUTING.md](CONTRIBUTING.md)에 있습니다.
