package com.gakdo.trace.diagnostics.trend.application;

import java.time.Instant;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 추이 점 하나(InfluxDB measurement 하나의 한 줄). 태그는 기기·OS 같은 분류, 필드는 진단에서 이미 저장한 숫자만 담는다.
 * 개인정보(이름·연락처·학번·컴퓨터 이름)는 넣지 않는다. 필드 값은 Long 또는 Double만 쓴다.
 */
public record TrendPoint(String measurement, SortedMap<String, String> tags, SortedMap<String, Number> fields,
        Instant time) {

    public TrendPoint {
        tags = new TreeMap<>(tags);
        fields = new TreeMap<>(fields);
    }

    public static TrendPoint of(String measurement, Map<String, String> tags, Map<String, Number> fields,
            Instant time) {
        return new TrendPoint(measurement, new TreeMap<>(tags), new TreeMap<>(fields), time);
    }
}
