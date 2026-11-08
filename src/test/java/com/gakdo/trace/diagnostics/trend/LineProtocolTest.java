package com.gakdo.trace.diagnostics.trend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.diagnostics.trend.application.TrendPoint;
import com.gakdo.trace.diagnostics.trend.infra.LineProtocol;

class LineProtocolTest {

    private static final Instant T = Instant.parse("2026-11-08T01:02:03.456Z");

    @Test
    @DisplayName("태그는 키 순서로, 정수는 i 접미사, 실수는 지수 없이, 시각은 밀리초로 한 줄을 만든다")
    void buildsLine() {
        TrendPoint p = TrendPoint.of("diagnostics_run", Map.of("os", "MACOS", "deviceSerial", "C02XYZ"),
                Map.of("battery_cycle_count", 123L, "battery_max_capacity_pct", 89.02, "bench_cpu_median",
                        1.0E10),
                T);
        assertThat(LineProtocol.line(p)).isEqualTo("diagnostics_run,deviceSerial=C02XYZ,os=MACOS "
                + "battery_cycle_count=123i,battery_max_capacity_pct=89.02,bench_cpu_median=10000000000 "
                + T.toEpochMilli());
    }

    @Test
    @DisplayName("measurement·태그 키·값·필드 키의 쉼표·등호·공백·역슬래시를 이스케이프하고 줄바꿈은 공백으로 바꾼다")
    void escapes() {
        TrendPoint p = TrendPoint.of("my run,x", Map.of("deviceModel", "MacBook Pro,18=1\\a\nb"),
                Map.of("a b=c,d", 1L), T);
        assertThat(LineProtocol.line(p)).isEqualTo("my\\ run\\,x,deviceModel=MacBook\\ Pro\\,18\\=1\\\\a\\ b "
                + "a\\ b\\=c\\,d=1i " + T.toEpochMilli());
    }

    @Test
    @DisplayName("빈 태그 값과 NaN·무한대 필드는 빼고, 쓸 필드가 없으면 줄을 만들지 않는다")
    void skipsInvalid() {
        TrendPoint p = TrendPoint.of("m", Map.of("os", " ", "deviceSerial", "S"),
                Map.of("nan", Double.NaN, "inf", Double.POSITIVE_INFINITY, "ok", 1.5), T);
        assertThat(LineProtocol.line(p)).isEqualTo("m,deviceSerial=S ok=1.5 " + T.toEpochMilli());
        assertThat(LineProtocol.line(TrendPoint.of("m", Map.of(), Map.of("nan", Double.NaN), T))).isNull();
    }
}
