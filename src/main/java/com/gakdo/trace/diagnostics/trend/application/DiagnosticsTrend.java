package com.gakdo.trace.diagnostics.trend.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.gakdo.trace.diagnostics.run.domain.BatteryReading;
import com.gakdo.trace.diagnostics.run.domain.BenchmarkResult;
import com.gakdo.trace.diagnostics.run.domain.DiagnosticRun;
import com.gakdo.trace.diagnostics.run.domain.SsdHealth;

/**
 * 저장한 진단 실행을 추이 점(measurement diagnostics_run)으로 바꾼다.
 * 태그: deviceSerial·os·deviceModel(있는 것만). 필드: 배터리 사이클·최대 용량, 벤치마크 중앙값, SSD 지표, 플래그 수.
 * 읽지 못한 값(null)은 필드를 빼고 넣는다. 시각은 에이전트가 수집한 시각(collectedAt).
 */
public final class DiagnosticsTrend {

    public static final String MEASUREMENT = "diagnostics_run";

    /** 이벤트와 같은 기준: 0~200%를 벗어난 최대 용량은 잘못 읽은 값으로 보고 쓰지 않는다. */
    private static final BigDecimal MAX_CAPACITY_PCT = new BigDecimal("200");

    private DiagnosticsTrend() {
    }

    public static TrendPoint of(DiagnosticRun run, String deviceModel, int flagCount) {
        Map<String, String> tags = new LinkedHashMap<>();
        putTag(tags, "deviceSerial", run.deviceSerial());
        putTag(tags, "os", run.os());
        putTag(tags, "deviceModel", deviceModel);

        Map<String, Number> fields = new LinkedHashMap<>();
        fields.put("flag_count", (long) flagCount);
        BatteryReading battery = run.battery();
        if (battery != null && !BatteryReading.NO_BATTERY.equals(battery.status())) {
            putLong(fields, "battery_cycle_count", battery.cycleCount());
            BigDecimal pct = battery.maxCapacityPct();
            if (pct != null && pct.signum() >= 0 && pct.compareTo(MAX_CAPACITY_PCT) <= 0) {
                fields.put("battery_max_capacity_pct", pct.doubleValue());
            }
        }
        SsdHealth ssd = run.ssd();
        if (ssd != null) {
            putLong(fields, "ssd_percent_used", ssd.percentUsed());
            putLong(fields, "ssd_available_spare_pct", ssd.availableSparePct());
            putLong(fields, "ssd_critical_warning", ssd.criticalWarning());
            putLong(fields, "ssd_power_on_hours", ssd.powerOnHours());
            putLong(fields, "ssd_media_errors", ssd.mediaErrors());
        }
        for (BenchmarkResult b : run.benchmarks()) {
            if (b.metric() != null && Double.isFinite(b.median())) {
                fields.put("bench_" + b.metric().toLowerCase(Locale.ROOT) + "_median", b.median());
            }
        }
        return TrendPoint.of(MEASUREMENT, tags, fields, run.collectedAt().toInstant());
    }

    private static void putTag(Map<String, String> tags, String key, String value) {
        if (value != null && !value.isBlank()) {
            tags.put(key, value);
        }
    }

    private static void putLong(Map<String, Number> fields, String key, Integer value) {
        if (value != null) {
            fields.put(key, value.longValue());
        }
    }
}
