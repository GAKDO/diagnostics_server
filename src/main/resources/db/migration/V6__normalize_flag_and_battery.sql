-- 정규화(11/20 ERD 점검). 헌법 "삭제는 다음 배포"에 맞춰 이번에는 칸을 지우지 않는다.

-- 1) inspection_flag.checkpoint_id를 더는 쓰지 않는다. 진단 실행이 시점을 정하므로(run_id → diagnostic_run.checkpoint_id)
--    같은 사실을 두 번 적는 3NF 이행 종속이다. 읽는 곳이 없던 칸이라 새 코드는 채우지 않는다.
--    NULL만 허용해 새 코드와 이전 코드가 모두 돌게 하고, 칸은 이 버전이 배포된 다음 마이그레이션에서 지운다.
ALTER TABLE diagnostics.inspection_flag ALTER COLUMN checkpoint_id DROP NOT NULL;

-- 2) diagnostic_run.raw_object_key는 V2부터 채우지 않는 칸이다(원본은 raw_body에 둔다). 이번에 코드 매핑만 빼고
--    칸은 다음 마이그레이션에서 지운다(이전 코드가 SELECT 목록에 넣기 때문).

-- 3) battery_reading.max_capacity_pct는 계산값이다(완충 ÷ 설계 × 100, 소수 둘째 자리 반올림).
--    조회·비교·이벤트에 쓰여 칸은 두되, 두 용량과 어긋난 값이 들어가지 못하게 DB가 같은 식으로 확인한다.
--    이미 있는 행은 막지 않도록 NOT VALID로 더한 뒤 확인을 시도하고, 어긋난 행이 있으면 알림만 남긴다(새 행은 늘 확인된다).
ALTER TABLE diagnostics.battery_reading ADD CONSTRAINT ck_battery_max_capacity_pct CHECK (
  max_capacity_pct IS NOT DISTINCT FROM
    CASE WHEN design_capacity > 0 AND full_charge_capacity IS NOT NULL
         THEN round(full_charge_capacity::numeric * 100 / design_capacity, 2) END
) NOT VALID;

DO $$
BEGIN
  ALTER TABLE diagnostics.battery_reading VALIDATE CONSTRAINT ck_battery_max_capacity_pct;
EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'battery_reading에 계산식과 다른 max_capacity_pct가 있습니다. 새 행만 확인합니다.';
END $$;
