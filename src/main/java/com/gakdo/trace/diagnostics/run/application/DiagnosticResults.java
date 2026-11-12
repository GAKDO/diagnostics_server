package com.gakdo.trace.diagnostics.run.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 응답 본문(contracts openapi/diagnostics-server.yaml의 같은 이름 스키마). 엔티티를 밖에 내보내지 않는다. */
public final class DiagnosticResults {

    private DiagnosticResults() {
    }

    /** 201 DiagnosticRunCreated */
    public record RunCreated(String runId, List<Flag> flags) {
    }

    /** InspectionFlag */
    public record Flag(String kind, String baseRunId, Map<String, Object> detail) {
    }

    /** 200 CheckpointDiagnostics: 시점 요약, 같은 렌탈 직전 시점 요약(없으면 null), 점검 플래그 */
    public record CheckpointDiagnostics(String checkpointId, RunSummary current, RunSummary previous,
            List<Flag> flags) {
    }

    public record RunSummary(String runId, OffsetDateTime collectedAt, String os, BatterySummary battery,
            SsdSummary ssd, List<ComponentSummary> components, List<BenchmarkSummary> benchmarks,
            RunChecks checks) {
    }

    /** RunChecks: 잠금·디스크 상태(11/7 추가), 보안 보호·위협 건수(11/12 추가). 옛 에이전트 진단은 RunSummary.checks가 null */
    public record RunChecks(String activationLock, String diskEncryption, String osDiskHealth,
            String securityProtection, Integer threatCount) {
    }

    /**
     * 배터리. maxCapacityPct는 서버 계산(완충 ÷ 설계 × 100). 읽지 못했으면 값은 null이고 skippedReason에 사유 코드
     * (contracts diagnostics-server 0.3.0). 배터리가 없는 기기면 이 객체 대신 null
     */
    public record BatterySummary(Integer cycleCount, Integer designCapacity, Integer fullChargeCapacity,
            String capacityUnit, Double maxCapacityPct, String condition, String skippedReason) {
    }

    /** SSD. 읽지 못했으면 값은 null이고 skippedReason에 사유 코드 */
    public record SsdSummary(Integer percentUsed, Integer availableSparePct, Integer criticalWarning,
            Integer powerOnHours, Integer mediaErrors, String skippedReason) {
    }

    public record ComponentSummary(String kind, String identifier) {
    }

    /** 공개 진단 요약(contracts CatalogDiagnostics, 11/8): RunSummary에서 runId·부품·잠금 상태를 뺀 것. */
    public record CatalogDiagnostics(String checkpointId, OffsetDateTime collectedAt, String os, BatterySummary battery,
            SsdSummary ssd, List<BenchmarkSummary> benchmarks) {
    }

    public record BenchmarkSummary(String metric, double median) {
    }
}
