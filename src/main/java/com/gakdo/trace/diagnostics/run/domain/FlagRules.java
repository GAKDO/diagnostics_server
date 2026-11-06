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
 */
public final class FlagRules {

    public static final String VERSION = "flag-rules-v1";
    public static final double CAPACITY_DROP_PCT = 5.0;
    public static final double BENCH_DROP_PCT = 15.0;

    public static final String COMPONENT_CHANGED = "COMPONENT_CHANGED";
    public static final String CAPACITY_DROP = "CAPACITY_DROP";
    public static final String BENCH_DROP = "BENCH_DROP";

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
            if (was == null || was.median() <= 0) {
                continue;
            }
            double dropPct = (was.median() - now.median()) / was.median() * 100.0;
            if (dropPct < BENCH_DROP_PCT) {
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
