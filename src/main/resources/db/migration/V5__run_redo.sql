-- 진단 다시 하기(11/13 민서 요청): 봉인 전 시점의 진단을 지우지 않고 "이전 진단"으로 돌린 뒤 새 진단을 받는다.
-- 시점당 지금 진단은 여전히 하나뿐이다(superseded_at이 비어 있는 행만 유일). 이전 진단은 이력으로 남는다.
ALTER TABLE diagnostics.diagnostic_run ADD COLUMN superseded_at timestamptz;
ALTER TABLE diagnostics.diagnostic_run DROP CONSTRAINT uq_run_checkpoint;
CREATE UNIQUE INDEX uq_run_checkpoint_current ON diagnostics.diagnostic_run(checkpoint_id) WHERE superseded_at IS NULL;
CREATE INDEX ix_run_checkpoint ON diagnostics.diagnostic_run(checkpoint_id);

-- 봉인된 시점(trace.record.sealed 소비, 큐 trace.diagnostics.seals). 봉인된 시점은 진단 다시 하기를 막는다(위변조 방지).
CREATE TABLE diagnostics.sealed_checkpoint (
  checkpoint_id varchar(21) PRIMARY KEY,
  sealed_at     timestamptz NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now()
);

-- 이 마이그레이션 전에 봉인된 시점은 이벤트를 다시 받을 수 없으므로 rental 스키마의 시점 상태에서 한 번만 옮긴다.
-- 다른 서버 스키마를 읽는 곳은 여기 한 번뿐이고, 표가 없거나 권한이 없으면(새 DB·시험 DB) 건너뛴다.
DO $$
BEGIN
  IF to_regclass('rental.checkpoint') IS NOT NULL THEN
    INSERT INTO diagnostics.sealed_checkpoint(checkpoint_id, sealed_at)
    SELECT public_id, coalesce(occurred_at, updated_at) FROM rental.checkpoint WHERE status = 'SEALED'
    ON CONFLICT (checkpoint_id) DO NOTHING;
  END IF;
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE '봉인된 시점을 옮기지 못했습니다(권한 없음). 이벤트로 받은 봉인부터 막습니다.';
END $$;
