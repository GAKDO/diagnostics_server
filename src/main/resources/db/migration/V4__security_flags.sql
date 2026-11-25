-- 보안 상태 점검(contracts 11/12 추가): 플래그 SECURITY_PROTECTION_OFF·THREAT_DETECTED
-- checks(jsonb)는 진단 JSON checks 그대로라 securityProtection·threatCount도 같은 칸에 들어간다(칸 추가 없음)
ALTER TABLE diagnostics.inspection_flag DROP CONSTRAINT IF EXISTS inspection_flag_kind_check;
ALTER TABLE diagnostics.inspection_flag ADD CONSTRAINT inspection_flag_kind_check CHECK (kind IN (
  'COMPONENT_CHANGED','CAPACITY_DROP','BENCH_DROP','ACTIVATION_LOCK_ON','DISK_ENCRYPTION_ON','DISK_HEALTH_WARNING',
  'SECURITY_PROTECTION_OFF','THREAT_DETECTED'));
