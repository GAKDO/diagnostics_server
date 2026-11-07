-- 잠금·디스크 상태 점검(contracts 11/7 추가): 진단 JSON checks와 상태 플래그 3종
-- checks는 진단 JSON의 checks 그대로({activationLock, diskEncryption, osDiskHealth}). 이 칸이 없던 옛 에이전트 진단은 null
ALTER TABLE diagnostics.diagnostic_run ADD COLUMN checks jsonb;

ALTER TABLE diagnostics.inspection_flag DROP CONSTRAINT IF EXISTS inspection_flag_kind_check;
ALTER TABLE diagnostics.inspection_flag ADD CONSTRAINT inspection_flag_kind_check CHECK (kind IN (
  'COMPONENT_CHANGED','CAPACITY_DROP','BENCH_DROP','ACTIVATION_LOCK_ON','DISK_ENCRYPTION_ON','DISK_HEALTH_WARNING'));
