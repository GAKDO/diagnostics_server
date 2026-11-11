# 데이터 모델: DI1

V1은 GAKDO/docs `db/V1__init.sql`의 diagnostics 절을 그대로 옮긴다. DI1에 필요한 칸은 V2로 더한다(헌법: Flyway는 새 파일만).

## V2__di1_run_upload.sql

| 테이블 | 바꾼 것 | 이유 |
| --- | --- | --- |
| diagnostic_run | `rental_id varchar(21) NOT NULL` | 직전 시점 찾기·시리얼 대조·CUSTOMER 범위·이벤트 봉투 (토큰 rentalId) |
| diagnostic_run | `collected_at timestamptz NOT NULL` | 조회 응답 `collectedAt` |
| diagnostic_run | `raw_body text NOT NULL`, `raw_object_key` NULL 허용 | 원본을 DB에 둠(clarify Q3) |
| diagnostic_run | `idempotency_key varchar(128) NOT NULL` | 같은 키 재생·충돌 |
| diagnostic_run | `benchmark_skipped_reason varchar(16)` | 벤치 생략 사유(NO_AC_POWER 등) |
| diagnostic_run | `UNIQUE (checkpoint_id)` (기존 ix_run_checkpoint 대신) | 시점당 하나(409), 동시 요청 방지 |
| diagnostic_run | `ix_run_rental (rental_id, created_at)` | 직전 실행 조회 |
| battery_reading | `status varchar(12) NOT NULL`, `skipped_reason varchar(16)` | OK·SKIPPED·NO_BATTERY와 사유 |
| ssd_health | `status varchar(8) NOT NULL`, `protocol varchar(8)` | SSD 읽기 결과·프로토콜 |

## 값 매핑 (diag.v1 → 표)

| JSON | 칸 |
| --- | --- |
| device.serial / os.type / agentVersion / conditions / consentedAt / collectedAt | diagnostic_run.device_serial / os / agent_version / conditions(jsonb) / consented_at / collected_at |
| components[] | hardware_component(kind, identifier, detail jsonb) |
| battery.cycleCount, designCapacity.value, fullChargeCapacity.value, unit, osMaxCapacityPercent, osCondition | battery_reading.cycle_count, design_capacity, full_charge_capacity, capacity_unit, os_max_capacity_pct, condition |
| (서버 계산) full ÷ design × 100, 소수 둘째 | battery_reading.max_capacity_pct |
| storage.percentageUsed, availableSpare, criticalWarning, powerOnHours, mediaErrors, skippedReason | ssd_health.percent_used, available_spare_pct, critical_warning, power_on_hours, media_errors, skipped_reason |
| benchmarks[] | benchmark_result(metric, trial_values jsonb, median) |

## 조회 응답 매핑 (CheckpointDiagnostics)

- `battery`: battery_reading이 NO_BATTERY면 null, 아니면 BatterySummary(SKIPPED면 값 모두 null).
- `ssd`: SsdSummary, `skippedReason`은 사유 코드(NO_PERMISSION 등), OK면 null.
- `previous`: 같은 렌탈에서 이 실행보다 먼저 만든 가장 최근 실행, 없으면 null.
- `flags`: inspection_flag(run_id = 이 실행) → kind·baseRunId(publicId)·detail. DI1에서는 비어 있다(DI2가 채움).
