package com.gakdo.trace.diagnostics.run.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.gakdo.trace.diagnostics.common.domain.DomainException.FieldError;

/**
 * JSON Schema로 표현하지 못해 서버가 확인하는 규칙(contracts diagnostics/README.md "서버(DI1)가 확인하는 것").
 * 시리얼 대조는 저장된 진단이 필요해 서비스가 한다.
 */
public final class UploadRules {

    /** battery_reading.max_capacity_pct numeric(5,2)의 최댓값 */
    static final BigDecimal MAX_PCT = new BigDecimal("999.99");

    private UploadRules() {
    }

    public static List<FieldError> check(DiagnosticUpload upload) {
        List<FieldError> errors = new ArrayList<>();
        checkBattery(upload.battery(), errors);
        checkStorage(upload.storage(), errors);
        checkBenchmarks(upload.benchmarks(), errors);
        return errors;
    }

    private static void checkBattery(DiagnosticUpload.Battery battery, List<FieldError> errors) {
        if (battery == null || battery.designCapacity() == null || battery.fullChargeCapacity() == null) {
            return;
        }
        if (!battery.designCapacity().unit().equals(battery.fullChargeCapacity().unit())) {
            errors.add(new FieldError("battery.fullChargeCapacity.unit", "설계 용량과 단위가 같아야 합니다"));
        } else if (maxCapacityPct(battery).compareTo(MAX_PCT) > 0) {
            errors.add(new FieldError("battery.fullChargeCapacity.value", "완충 용량이 설계 용량의 10배를 넘습니다"));
        }
    }

    private static void checkStorage(DiagnosticUpload.Storage storage, List<FieldError> errors) {
        if (storage == null) {
            return;
        }
        if (storage.mediaErrors() != null && storage.mediaErrors() > Integer.MAX_VALUE) {
            errors.add(new FieldError("storage.mediaErrors", "값이 너무 큽니다"));
        }
        if (storage.powerOnHours() != null && storage.powerOnHours() > Integer.MAX_VALUE) {
            errors.add(new FieldError("storage.powerOnHours", "값이 너무 큽니다"));
        }
    }

    private static void checkBenchmarks(List<DiagnosticUpload.Benchmark> benchmarks, List<FieldError> errors) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < benchmarks.size(); i++) {
            DiagnosticUpload.Benchmark b = benchmarks.get(i);
            if (!seen.add(b.metric())) {
                errors.add(new FieldError("benchmarks[" + i + "].metric", "같은 지표가 두 번 있습니다"));
            }
            if (Double.compare(middle(b.trials()), b.median()) != 0) {
                errors.add(new FieldError("benchmarks[" + i + "].median", "median은 trials의 가운데 값이어야 합니다"));
            }
        }
    }

    /** 3회 측정의 가운데 값(정렬한 두 번째). */
    static double middle(List<Double> trials) {
        List<Double> sorted = trials.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    /** 효율 % = 완충 ÷ 설계 × 100, 소수 둘째 자리 반올림. 두 용량과 같은 단위일 때만 부른다. */
    public static BigDecimal maxCapacityPct(DiagnosticUpload.Battery battery) {
        return BigDecimal.valueOf(battery.fullChargeCapacity().value())
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(battery.designCapacity().value()), 2, RoundingMode.HALF_UP);
    }
}
