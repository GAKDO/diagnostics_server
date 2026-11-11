# 분석: DI1 (명세·계획·작업 대조)

| 확인 | 결과 |
| --- | --- |
| 수용 기준 → 테스트 | backlog 수용 기준 4개: 스키마 위반 거부(422, 계약 예시 invalid 4개·필드 경로), 생략 항목 사유 저장(SSD NO_PERMISSION·벤치 NO_AC_POWER, 실제 DB 포함), 같은 시점 두 번(계약대로 409·같은 키 재생), 결과 해시(원본 바이트 SHA-256) 모두 `@DisplayName`으로 있음 |
| 계약 대조 | 경로 2개·상태 코드·오류 code(ErrorCodeTest가 codes.gen 사본과 대조)·응답 형식(`DiagnosticRunCreated`·`CheckpointDiagnostics`)·이벤트(사본 스키마로 검증)가 contracts develop과 같음. 계약 변경 없음 |
| 백로그와 계약 차이 | 400 → 422 DIAGNOSTIC_SCHEMA_INVALID, "새 실행으로 쌓인다" → 409 DIAGNOSTIC_ALREADY_UPLOADED. 계약을 따름(clarify Q1) |
| 범위 확인 | AGENT scope·rentalId NanoID 확인, 조회는 ADMIN 또는 rentals에 렌탈이 있는 CUSTOMER, 촬영 JWT 403 (decisions.md 11/2 GW1) |
| DDL | V1 = docs V1 diagnostics 절 그대로, V2로 rental_id·collected_at·raw_body·idempotency_key·benchmark_skipped_reason·시점 유일·배터리/SSD status 추가. docs ddl.md에 반영 |
| 남은 위험 | (11/2 해소: 시리얼은 AGENT 토큰 deviceSerial과 대조, 배터리 생략 사유는 응답에 나감) DB 커밋 뒤 Rabbit 커밋 실패 시 이벤트만 빠짐 |

**Converged**: 작업 T001~T014 완료, `TRACE_IT_LOCAL=1 ./mvnw verify` 통과(테스트 46개, RabbitMQ 시험 1개는 Docker 없어 건너뜀·CI에서 돎, 줄 커버리지 83.5%).
