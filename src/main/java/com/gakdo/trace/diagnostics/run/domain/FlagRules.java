package com.gakdo.trace.diagnostics.run.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 점검 플래그 규칙 flag-rules-v1(FR-DIA-06). 같은 렌탈의 바로 앞 진단(기준)과 이번 진단을 비교한다.
 * <ul>
 * <li>COMPONENT_CHANGED: 부품 종류마다 식별자·시리얼 묶음이 달라짐(종류마다 플래그 하나)</li>
 * <li>CAPACITY_DROP: 배터리 최대 용량(서버 계산 %)이 {@value #CAPACITY_DROP_PCT}%p 이상 떨어짐</li>
 * <li>BENCH_DROP: 벤치마크 중앙값이 {@value #BENCH_DROP_PCT}% 이상 떨어짐(지표마다, 모두 클수록 좋은 값)</li>
 * </ul>
 * 임계값은 설계서대로 파일럿 1차 측정 편차로 다시 정한다. 그 전까지는 3회 측정 편차(agent #3 기준 수 %)보다 넉넉히 둔다.
 * 비교할 값이 한쪽이라도 없으면(생략·못 읽음) 그 항목은 플래그를 붙이지 않는다.
 * <p>
 * 상태 플래그({@link #states})는 비교 없이 이번 진단의 checks만 본다(11/7 계약 추가). 첫 진단(출고)에도 붙는다.
 * <ul>
 * <li>ACTIVATION_LOCK_ON: macOS 활성화 잠금(나의 Mac 찾기)이 켜짐</li>
 * <li>DISK_ENCRYPTION_ON: FileVault·BitLocker가 켜짐</li>
 * <li>DISK_HEALTH_WARNING: OS가 본 SSD SMART 상태가 정상이 아님</li>
 * <li>SECURITY_PROTECTION_OFF: OS 기본 보안 보호(Defender 백신·실시간 보호, Gatekeeper·SIP)가 꺼짐(11/12 추가)</li>
 * </ul>
 * 위협 플래그({@link #threat}, 11/12 추가): THREAT_DETECTED는 Defender 위협 탐지 건수가 앞 진단보다 늘었을 때,
 * 앞 진단이 없거나 그 건수를 못 읽었으면 이번 건수가 1 이상일 때 붙는다(대여 전부터 있던 기록을 매번 다시 올리지 않게).
 * 못 읽은 값(null)이나 checks가 없는 옛 진단은 플래그를 붙이지 않는다.
 */
public final class FlagRules {

    public static final String VERSION = "flag-rules-v1";
    public static final double CAPACITY_DROP_PCT = 5.0;
    public static final double BENCH_DROP_PCT = 15.0;

    public static final String COMPONENT_CHANGED = "COMPONENT_CHANGED";
    public static final String CAPACITY_DROP = "CAPACITY_DROP";
    public static final String BENCH_DROP = "BENCH_DROP";
    public static final String ACTIVATION_LOCK_ON = "ACTIVATION_LOCK_ON";
    public static final String DISK_ENCRYPTION_ON = "DISK_ENCRYPTION_ON";
    public static final String DISK_HEALTH_WARNING = "DISK_HEALTH_WARNING";
    public static final String SECURITY_PROTECTION_OFF = "SECURITY_PROTECTION_OFF";
    public static final String THREAT_DETECTED = "THREAT_DETECTED";
    public static final String STATES_VERSION = "state-rules-v1";

    private FlagRules() {
    }

    /** 붙일 플래그(종류 + 근거). 저장·이벤트는 application이 한다. */
    public record Draft(String kind, Map<String, Object> detail) {
    }

    public static List<Draft> compare(DiagnosticRun base, DiagnosticRun current) {
        List<Draft> drafts = new ArrayList<>();
        drafts.addAll(componentChanges(base.components(), current.components()));
        capacityDrop(base.battery(), current.battery()).ifPresent(drafts::add);
        drafts.addAll(benchDrops(base.benchmarks(), current.benchmarks()));
        return drafts;
    }

    /** 이번 진단의 잠금·디스크 상태 플래그. 앞 진단이 없어도 붙는다. */
    public static List<Draft> states(DiagnosticRun current) {
        DiagnosticUpload.Checks c = current.checks();
        List<Draft> drafts = new ArrayList<>();
        if (c == null) {
            return drafts;
        }
        if ("ON".equals(c.activationLock())) {
            drafts.add(state(ACTIVATION_LOCK_ON, "activationLock", c.activationLock(), current.os()));
        }
        if ("ON".equals(c.diskEncryption())) {
            drafts.add(state(DISK_ENCRYPTION_ON, "diskEncryption", c.diskEncryption(), current.os()));
        }
        if ("WARNING".equals(c.osDiskHealth())) {
            drafts.add(state(DISK_HEALTH_WARNING, "osDiskHealth", c.osDiskHealth(), current.os()));
        }
        if ("OFF".equals(c.securityProtection())) {
            drafts.add(state(SECURITY_PROTECTION_OFF, "securityProtection", c.securityProtection(), current.os()));
        }
        return drafts;
    }

    /** 위협 건수 플래그. 비교했으면 compared=true(기준은 앞 진단), 아니면 기준은 이번 진단 자신. */
    public record ThreatDraft(Draft draft, boolean compared) {
    }

    /** base는 같은 렌탈 바로 앞 진단(없으면 null). 이번 건수를 못 읽었으면 플래그 없음. */
    public static java.util.Optional<ThreatDraft> threat(DiagnosticRun base, DiagnosticRun current) {
        Integer now = threatCount(current);
        Integer before = base == null ? null : threatCount(base);
        if (now == null) {
            return java.util.Optional.empty();
        }
        boolean compared = before != null;
        if (compared ? now <= before : now < 1) {
            return java.util.Optional.empty();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rule", STATES_VERSION);
        detail.put("field", "threatCount");
        detail.put("before", before);
        detail.put("after", now);
        detail.put("os", current.os());
        return java.util.Optional.of(new ThreatDraft(new Draft(THREAT_DETECTED, detail), compared));
    }

    private static Integer threatCount(DiagnosticRun run) {
        return run.checks() == null ? null : run.checks().threatCount();
    }

    private static Draft state(String kind, String field, String value, String os) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rule", STATES_VERSION);
        detail.put("field", field);
        detail.put("value", value);
        detail.put("os", os);
        return new Draft(kind, detail);
    }

    static List<Draft> componentChanges(List<HardwareComponent> before, List<HardwareComponent> after) {
        Map<String, TreeSet<String>> b = signatures(before);
        Map<String, TreeSet<String>> a = signatures(after);
        TreeSet<String> kinds = new TreeSet<>(b.keySet());
        kinds.addAll(a.keySet());
        List<Draft> drafts = new ArrayList<>();
        for (String kind : kinds) {
            TreeSet<String> was = b.getOrDefault(kind, new TreeSet<>());
            TreeSet<String> now = a.getOrDefault(kind, new TreeSet<>());
            // 한쪽에서 그 종류를 아예 못 읽은 경우는 바뀜이 아니라 읽기 차이일 수 있어 넘긴다
            if (was.isEmpty() || now.isEmpty() || was.equals(now)) {
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("rule", VERSION);
            detail.put("componentKind", kind);
            detail.put("before", List.copyOf(was));
            detail.put("after", List.copyOf(now));
            drafts.add(new Draft(COMPONENT_CHANGED, detail));
        }
        return drafts;
    }

    /** 종류 → "식별자#시리얼" 묶음. 시리얼이 없으면 식별자만 본다. */
    private static Map<String, TreeSet<String>> signatures(List<HardwareComponent> components) {
        Map<String, TreeSet<String>> byKind = new TreeMap<>();
        for (HardwareComponent c : components) {
            Object serial = c.detail() == null ? null : c.detail().get("serial");
            String signature = serial == null ? c.identifier() : c.identifier() + "#" + serial;
            byKind.computeIfAbsent(c.kind(), k -> new TreeSet<>()).add(signature);
        }
        return byKind;
    }

    static java.util.Optional<Draft> capacityDrop(BatteryReading before, BatteryReading after) {
        if (before == null || after == null || before.maxCapacityPct() == null || after.maxCapacityPct() == null) {
            return java.util.Optional.empty();
        }
        BigDecimal drop = before.maxCapacityPct().subtract(after.maxCapacityPct());
        if (drop.doubleValue() < CAPACITY_DROP_PCT) {
            return java.util.Optional.empty();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rule", VERSION);
        detail.put("beforePct", before.maxCapacityPct().doubleValue());
        detail.put("afterPct", after.maxCapacityPct().doubleValue());
        detail.put("dropPct", drop.setScale(2, RoundingMode.HALF_UP).doubleValue());
        detail.put("thresholdPct", CAPACITY_DROP_PCT);
        detail.put("beforeCycleCount", before.cycleCount());
        detail.put("afterCycleCount", after.cycleCount());
        return java.util.Optional.of(new Draft(CAPACITY_DROP, detail));
    }

    static List<Draft> benchDrops(List<BenchmarkResult> before, List<BenchmarkResult> after) {
        List<Draft> drafts = new ArrayList<>();
        for (BenchmarkResult now : after) {
            BenchmarkResult was = before.stream().filter(b -> Objects.equals(b.metric(), now.metric()))
                    .findFirst().orElse(null);
            // 앞 기록이 없거나 0이면 비교하지 않는다. 비교할 수 있을 때만 하락률을 구한다
            double dropPct = (was == null || was.median() <= 0) ? 0.0
                    : (was.median() - now.median()) / was.median() * 100.0;
            if (was == null || dropPct < BENCH_DROP_PCT) {
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("rule", VERSION);
            detail.put("metric", now.metric());
            detail.put("before", was.median());
            detail.put("after", now.median());
            detail.put("dropPct", BigDecimal.valueOf(dropPct).setScale(2, RoundingMode.HALF_UP).doubleValue());
            detail.put("thresholdPct", BENCH_DROP_PCT);
            drafts.add(new Draft(BENCH_DROP, detail));
        }
        return drafts;
    }
}
