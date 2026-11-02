# 기능 명세: DI1 진단 수신·저장

**브랜치**: `claude/project-thread-png5yk` (Spec Kit 기본 `feature/001-diagnostics-upload` 대신 세션 지정 브랜치)
**작성**: 2026-11-02
**상태**: 확정(clarify 반영)
**요구사항**: FR-DIA-01~05(서버 쪽), FR-DIA-06의 조회 틀(플래그 계산은 DI2), 리포트의 배터리·SSD 표시 데이터(11/2 저녁 우선순위 "DI1 + 리포트에 진단 표시")
**계약**: GAKDO/contracts develop `openapi/diagnostics-server.yaml` 0.2.0, `diagnostics/result.schema.json`(`diag.v1`), `events/trace.diagnostics.recorded.schema.json` 1.0, `codes.yaml` 0.5.0 이후
**입력(backlog.md)**: "에이전트가 페어링 JWT로 보낸 진단 결과를 contracts의 JSON Schema로 검증하고 시점에 붙여 저장한다(FR-DIA-01~05의 서버 쪽). 결과 해시를 남기고 trace 이벤트를 낸다. 수용 기준: 스키마에 맞지 않는 결과는 400 Problem Details로 거부된다. 생략 항목은 사유와 함께 저장된다. 같은 시점에 두 번 올리면 새 실행으로 쌓인다. 스키마는 design/ddl.md의 diagnostics 절이다."

> 백로그 문장과 계약이 다른 두 곳은 **계약을 따른다**(헌법 I, 계약이 나중에 정해졌고 11/3 고정 대상): 스키마 위반은 400이 아니라 **422 DIAGNOSTIC_SCHEMA_INVALID**(본문이 JSON이 아니면 400 VALIDATION_FAILED), 같은 시점 두 번째 업로드는 새 실행이 아니라 **409 DIAGNOSTIC_ALREADY_UPLOADED**(같은 Idempotency-Key는 처음 응답 재생).

## 사용자 시나리오와 테스트

### 이야기 1 — 진단 결과 올리기 (P1)

진단 에이전트(AGENT 토큰: scope=checkpointId, rentalId 클레임)가 사용자 동의 뒤 `POST /api/v1/diagnostics/runs`로 `diag.v1` JSON을 올린다. 서버는 스키마와 서버 규칙으로 검증하고, 시점(토큰의 checkpointId)에 붙여 저장하고, 원본 SHA-256을 남기고 `trace.diagnostics.recorded`를 낸다.

**독립 시험**: contracts 예시 `valid/macos.json`을 올리면 201과 runId, DB에 실행·부품·배터리·SSD·벤치마크 행, 이벤트 하나.

**수용 시나리오**
1. **주어진** AGENT 토큰과 유효한 진단 JSON **하면** 업로드 **그러면** 201 `{runId, flags: []}`, runId는 NanoID 21자.
2. **주어진** 저장된 실행 **그러면** `sha256`은 요청 본문 바이트 그대로의 SHA-256이고 원본 본문도 함께 남는다(해시 체인 재료).
3. **주어진** 업로드 성공 **그러면** `trace.events`에 라우팅 키 `trace.diagnostics.recorded` 이벤트 하나(봉투 rentalId=토큰 rentalId, checkpointId=토큰 scope, runId, sha256, baseRunId, flags=[]). 같은 렌탈에 앞선 진단이 없으면 baseRunId=null, 있으면 바로 앞 실행의 runId.
4. **주어진** 스키마에 맞지 않는 결과(예: 모르는 키 `computerName`, 단위 없는 용량, 사유 없는 SKIPPED) **그러면** 422 DIAGNOSTIC_SCHEMA_INVALID, `errors[]`에 필드 경로와 이유, 아무것도 저장되지 않고 이벤트도 없다(스키마에 맞지 않는 결과는 Problem Details로 거부된다).
5. **주어진** 스키마로는 통과하지만 서버 규칙 위반: 두 용량 단위가 다름, median이 trials의 가운데 값이 아님, 같은 지표가 두 번 **그러면** 422 DIAGNOSTIC_SCHEMA_INVALID(필드 `battery.fullChargeCapacity.unit`·`benchmarks[i].median`·`benchmarks[i].metric`).
6. **주어진** 본문이 JSON이 아님 **그러면** 400 VALIDATION_FAILED.
7. **주어진** 배터리 `status: SKIPPED, skippedReason: NO_PERMISSION`이나 SSD SKIPPED, 벤치마크 `NO_AC_POWER` **그러면** 201, 값은 null로, 사유는 그대로 저장된다(생략 항목은 사유와 함께 저장된다).
8. **주어진** `device.serial`이 AGENT 토큰의 `deviceSerial`(렌탈 기기 등록 시리얼)과 다름 **그러면** 422 DEVICE_SERIAL_MISMATCH, 저장 안 함. `deviceSerial` 클레임이 없는 AGENT 토큰은 403 FORBIDDEN.

### 이야기 2 — 다시 보내기와 중복 (P1)

**수용 시나리오**
1. **주어진** 같은 Idempotency-Key·같은 본문으로 다시 보냄 **그러면** 처음 응답(201, 같은 runId)을 그대로 돌려주고 새 행·새 이벤트는 없다.
2. **주어진** 같은 키에 다른 본문 **그러면** 422 IDEMPOTENCY_KEY_CONFLICT.
3. **주어진** 이미 진단이 있는 시점에 다른 키로 올림 **그러면** 409 DIAGNOSTIC_ALREADY_UPLOADED(같은 시점에 두 번 올리면 쌓지 않고 막는다, 계약).
4. **주어진** Idempotency-Key 없음 또는 8자 미만 **그러면** 400 VALIDATION_FAILED.

### 이야기 3 — 리포트용 시점 진단 조회 (P1)

웹 리포트(WE3)가 시점마다 배터리·SSD 상태를 보여 주도록 `GET /api/v1/diagnostics/checkpoints/{checkpointId}`가 시점 요약, 같은 렌탈 바로 앞 시점 요약(없으면 null), 점검 플래그를 돌려준다.

**수용 시나리오**
1. **주어진** 출고 시점 진단 **하면** 조회 **그러면** 200, `current.battery`(cycleCount·designCapacity·fullChargeCapacity·capacityUnit·maxCapacityPct·condition), `current.ssd`(percentUsed·availableSparePct·criticalWarning·powerOnHours·mediaErrors·skippedReason), components(kind·identifier), benchmarks(metric·median), `previous: null`, `flags: []`.
2. **주어진** 출고·반납 두 시점 진단 **하면** 반납 시점 조회 **그러면** `previous`가 출고 시점 요약이다(두 번 측정한 값을 한 번의 조회로).
3. **주어진** maxCapacityPct **그러면** 서버가 완충 ÷ 설계 × 100을 소수 둘째 자리로 계산한다(OS 값이 아님). 용량을 못 읽었으면 null.
4. **주어진** SSD SKIPPED **그러면** ssd 값은 null, `skippedReason`에 사유 코드. 배터리가 없는 기기(NO_BATTERY)면 `battery: null`.
5. **주어진** ADMIN **그러면** 어느 시점이든 200. CUSTOMER는 `rentals` 클레임에 그 시점의 렌탈이 있을 때만, 아니면 403 OUT_OF_SCOPE. 진단이 없는 시점은 404 NOT_FOUND.

### 이야기 4 — 범위 확인 (P1)

gateway는 역할까지만 막으므로 시점 범위는 서버가 토큰으로 다시 본다(decisions.md 11/2 GW1).

1. **주어진** AGENT 토큰에 scope(checkpointId)나 rentalId가 NanoID가 아님 **그러면** 403 FORBIDDEN.
2. **주어진** ADMIN·CUSTOMER·BOOTH 토큰으로 업로드 **그러면** 403 FORBIDDEN. AGENT 토큰으로 조회 **그러면** 403.
3. **주어진** 토큰 없음 **그러면** 401 UNAUTHENTICATED. `/api/v1/diagnostics/ping`은 토큰 없이 200.

### 경계 상황
- 동시에 같은 시점을 두 번 올리면 DB 유일 제약으로 하나만 저장되고 다른 하나는 409(같은 키면 처음 응답).
- RabbitMQ 채널 트랜잭션은 DB 트랜잭션에 묶여(CA1과 같은 방식) DB가 롤백되면 이벤트도 나가지 않는다. 드물게 DB 커밋 뒤 Rabbit 커밋이 실패하면 이벤트만 빠진다(로그 ERROR, 아웃박스는 VI 단순하게 원칙으로 미룸).
- 렌탈 기기 시리얼은 다른 서버의 데이터라 직접 읽지 않는다(서버끼리 REST 금지). 관리자가 페어링 코드를 만들 때 정한 등록 시리얼이 AGENT 토큰 `deviceSerial` 클레임으로 오고, 그것과 대조한다(clarify Q2 갱신, contracts auth-server 0.2.0).

## 요구사항

### 기능 요구사항
- **FR-001** AGENT 토큰만 업로드할 수 있고, 시점은 토큰 scope, 렌탈은 토큰 rentalId에서 읽는다(본문에 시점 없음).
- **FR-002** 본문은 contracts `diag.v1` JSON Schema(2020-12)로 검증한다. 스키마 사본은 contracts에서 그대로 복사해 두고 `scripts/sync-contracts.sh`로 맞춘다.
- **FR-003** 서버 규칙(스키마 밖): 두 용량 단위 일치, median = 정렬한 trials의 가운데 값, 지표 중복 금지, AGENT 토큰 deviceSerial과 시리얼 일치.
- **FR-004** 원본 본문과 그 SHA-256(소문자 hex 64자)을 저장한다.
- **FR-005** 실행·부품·배터리·SSD·벤치마크를 ddl.md diagnostics 표에 저장하고, 생략 사유(배터리·SSD·벤치마크)를 저장한다.
- **FR-006** 저장과 같은 트랜잭션으로 `trace.diagnostics.recorded`를 낸다(message_id = eventId).
- **FR-007** 시점당 진단 하나(409), Idempotency-Key 재생·충돌(422).
- **FR-008** 시점 진단 조회는 계약 `CheckpointDiagnostics` 형식으로, 직전 시점 요약과 플래그(DI1에서는 DI2가 쓴 행이 없으면 빈 배열)를 함께 준다.
- **FR-009** 오류는 모두 Problem Details(`code`는 codes.yaml error_code).

### 핵심 데이터
- 진단 실행(diagnostic_run): publicId, 시점·렌탈, 시리얼, OS, 에이전트 버전, 측정 조건, 측정·동의 시각, 원본·해시, 멱등 키, 벤치 생략 사유.
- 부품(hardware_component), 배터리(battery_reading), SSD(ssd_health), 벤치마크(benchmark_result), 점검 플래그(inspection_flag, DI2가 채움).

## 성공 기준
- **SC-001** contracts `examples/diagnostics/valid/` 3개는 모두 201, `invalid/` 4개는 모두 422.
- **SC-002** 한 번의 조회로 두 시점의 배터리·SSD 값을 받는다.
- **SC-003** `./mvnw verify` 통과, 줄 커버리지 40% 이상.

## Clarifications

### Session 2026-11-02 (사용자 지시 "묻지 말고 진행"에 따라 추천안 채택)
- Q1: 백로그(400·새 실행으로 쌓기)와 계약(422·409)이 다르면? → **계약을 따른다.** 계약이 C1·C2에서 나중에 정해졌고 11/3 고정 대상이다.
- Q2: 렌탈 기기 시리얼을 어디서 얻나(AGENT 토큰에 없음)? → **같은 렌탈의 앞선 진단 시리얼과 대조**한다. 첫 진단(보통 출고)은 기준이 된다. 기기 등록 시리얼과의 대조는 AGENT 토큰에 deviceSerial 클레임이 생기면 더한다(후속, 사용자 확인).
  - **갱신(11/2 민서 결정)**: AGENT 토큰에 `deviceSerial` 클레임을 넣고 그것과 대조한다(첫 진단도 대조). 앞선 진단과의 대조는 뺀다. 배터리 생략 사유도 응답 `BatterySummary.skippedReason`으로 내보낸다(contracts diagnostics-server 0.3.0).
- Q3: 원본 진단 JSON을 MinIO(raw_object_key)에 둘까? → **DB에 원문 그대로(text) 둔다.** 수십 KB라 충분하고, 학교 MinIO는 학습 데이터 전용이다(11/2). raw_object_key는 NULL 허용으로 바꾼다.
- Q4: "직전 시점"은? → 같은 렌탈에서 이 실행보다 먼저 저장된 가장 최근 실행(출고 → 수령 → 반납 순으로 올라온다).
- Q5: 배터리 생략 사유는 조회 응답 어디에? → 계약 `BatterySummary`에 칸이 없어 **DB에만 저장**하고 응답에서는 값이 모두 null이다. 화면에 보여야 하면 계약에 `skippedReason`을 더하는 변경(값 추가)을 사용자에게 제안한다.
