package com.gakdo.trace.diagnostics.trend.infra;

import java.math.BigDecimal;
import java.util.Map;

import com.gakdo.trace.diagnostics.trend.application.TrendPoint;

/**
 * InfluxDB v2 line protocol 한 줄을 만든다(시각은 밀리초, precision=ms).
 * 이스케이프: measurement는 쉼표·공백, 태그 키·값과 필드 키는 쉼표·등호·공백, 모두 역슬래시도. 줄바꿈은 공백으로 바꾼다.
 * 정수 필드는 접미사 i, 실수는 지수 표기 없이 쓴다. NaN·무한대 필드와 빈 태그 값은 뺀다.
 */
public final class LineProtocol {

    private LineProtocol() {
    }

    /** 쓸 필드가 하나도 없으면 null(line protocol은 필드가 하나 이상 있어야 한다). */
    public static String line(TrendPoint point) {
        StringBuilder fields = new StringBuilder();
        for (Map.Entry<String, Number> f : point.fields().entrySet()) {
            String value = fieldValue(f.getValue());
            if (value == null) {
                continue;
            }
            if (!fields.isEmpty()) {
                fields.append(',');
            }
            fields.append(escapeKey(f.getKey())).append('=').append(value);
        }
        if (fields.isEmpty()) {
            return null;
        }
        StringBuilder line = new StringBuilder(escapeMeasurement(point.measurement()));
        point.tags().forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                line.append(',').append(escapeKey(k)).append('=').append(escapeKey(v));
            }
        });
        line.append(' ').append(fields);
        if (point.time() != null) {
            line.append(' ').append(point.time().toEpochMilli());
        }
        return line.toString();
    }

    static String fieldValue(Number n) {
        if (n == null) {
            return null;
        }
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return n.longValue() + "i";
        }
        double d = n.doubleValue();
        if (!Double.isFinite(d)) {
            return null;
        }
        return BigDecimal.valueOf(d).toPlainString();
    }

    static String escapeMeasurement(String s) {
        return clean(s).replace("\\", "\\\\").replace(",", "\\,").replace(" ", "\\ ");
    }

    static String escapeKey(String s) {
        return clean(s).replace("\\", "\\\\").replace(",", "\\,").replace("=", "\\=").replace(" ", "\\ ");
    }

    private static String clean(String s) {
        return s.replace('\n', ' ').replace('\r', ' ');
    }
}
