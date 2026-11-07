package com.gakdo.trace.diagnostics.run.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 진단 결과 JSON v1(contracts diagnostics/result.schema.json, {@code diag.v1}). JSON Schema 검증을 통과한 본문만 이 형태로 바꾼다.
 * 읽지 못한 값은 null이다(헌법 III). 코드 값(os·kind·status·사유)은 스키마가 codes.yaml 값만 받으므로 문자열로 둔다.
 */
public record DiagnosticUpload(
        String schemaVersion,
        String agentVersion,
        OffsetDateTime collectedAt,
        OffsetDateTime consentedAt,
        Os os,
        Device device,
        Map<String, Object> conditions,
        List<Component> components,
        Battery battery,
        Storage storage,
        List<Benchmark> benchmarks,
        String benchmarkSkippedReason,
        List<String> sources,
        Checks checks) {

    /** 잠금·디스크 상태(11/7 추가, 선택). 칸이 없는 옛 진단은 checks 자체가 null, 못 읽은 값은 각각 null */
    public record Checks(String activationLock, String diskEncryption, String osDiskHealth) {
    }

    public record Os(String type, String version, String arch) {
    }

    public record Device(String serial, String manufacturer, String model, String modelName) {
    }

    public record Component(String kind, String identifier, Map<String, Object> detail) {
    }

    /** 배터리 용량. 값과 단위를 함께 두고 환산하지 않는다. */
    public record Capacity(int value, String unit) {
    }

    public record Battery(String status, String skippedReason, Integer cycleCount, Capacity designCapacity,
            Capacity fullChargeCapacity, Integer osMaxCapacityPercent, String osCondition) {
    }

    public record Storage(String status, String skippedReason, String protocol, Integer percentageUsed,
            Integer availableSpare, Integer criticalWarning, Long mediaErrors, Long powerOnHours) {
    }

    public record Benchmark(String metric, String unit, List<Double> trials, double median) {
    }
}
