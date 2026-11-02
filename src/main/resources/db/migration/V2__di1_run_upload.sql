-- DI1 진단 수신·저장에 필요한 칸 (specs/001-diagnostics-upload/data-model.md)
-- 다른 서버의 행은 FK 없이 public_id 값으로 둔다(rental_id = rental.rental.public_id, checkpoint_id = rental.checkpoint.public_id).

ALTER TABLE diagnostics.diagnostic_run
  ADD COLUMN rental_id                varchar(21)  NOT NULL,   -- AGENT 토큰 rentalId. 직전 시점·시리얼 대조·조회 범위·이벤트 봉투
  ADD COLUMN collected_at             timestamptz  NOT NULL,   -- 측정을 마친 시각(collectedAt)
  ADD COLUMN raw_body                 text         NOT NULL,   -- 올린 원본 JSON 그대로. sha256은 이 바이트(UTF-8)의 해시
  ADD COLUMN idempotency_key          varchar(128) NOT NULL,   -- 같은 키 재생·다른 본문 422
  ADD COLUMN benchmark_skipped_reason varchar(16)  CHECK (benchmark_skipped_reason IN ('NO_PERMISSION','UNSUPPORTED','TOOL_ERROR','NO_AC_POWER')),
  ALTER COLUMN raw_object_key DROP NOT NULL;                   -- 원본은 DB에 둔다(MinIO 안 씀, clarify Q3)

-- 시점당 진단 하나(계약 409 DIAGNOSTIC_ALREADY_UPLOADED). 동시에 두 번 와도 하나만 저장된다
DROP INDEX diagnostics.ix_run_checkpoint;
ALTER TABLE diagnostics.diagnostic_run ADD CONSTRAINT uq_run_checkpoint UNIQUE (checkpoint_id);
CREATE INDEX ix_run_rental ON diagnostics.diagnostic_run(rental_id, created_at);

ALTER TABLE diagnostics.battery_reading
  ADD COLUMN status         varchar(12) NOT NULL CHECK (status IN ('OK','SKIPPED','NO_BATTERY')),
  ADD COLUMN skipped_reason varchar(16) CHECK (skipped_reason IN ('NO_PERMISSION','UNSUPPORTED','TOOL_ERROR'));

ALTER TABLE diagnostics.ssd_health
  ADD COLUMN status   varchar(8) NOT NULL CHECK (status IN ('OK','SKIPPED')),
  ADD COLUMN protocol varchar(8) CHECK (protocol IN ('NVME','ATA'));

CREATE INDEX ix_component_run ON diagnostics.hardware_component(run_id);
CREATE INDEX ix_flag_run ON diagnostics.inspection_flag(run_id);
