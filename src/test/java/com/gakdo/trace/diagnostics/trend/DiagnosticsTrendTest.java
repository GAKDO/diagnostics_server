package com.gakdo.trace.diagnostics.trend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.diagnostics.common.infra.PublicIds;
import com.gakdo.trace.diagnostics.common.infra.Times;
import com.gakdo.trace.diagnostics.run.domain.DiagnosticRun;
import com.gakdo.trace.diagnostics.run.domain.DiagnosticUpload;
import com.gakdo.trace.diagnostics.support.Examples;
import com.gakdo.trace.diagnostics.trend.application.DiagnosticsTrend;
import com.gakdo.trace.diagnostics.trend.application.TrendPoint;

class DiagnosticsTrendTest {

    @Test
    @DisplayName("저장한 진단을 diagnostics_run 점으로 바꾸며 기기·OS 태그와 배터리·SSD·벤치마크 숫자만 담는다")
    void runToPoint() throws Exception {
        byte[] body = Examples.bytes(Examples.MACOS);
        DiagnosticUpload upload = Examples.JSON.findAndRegisterModules().readValue(body, DiagnosticUpload.class);
        DiagnosticRun run = DiagnosticRun.of(PublicIds.next(), PublicIds.next(), PublicIds.next(), "k", upload,
                new DiagnosticRun.RawBody(new String(body, StandardCharsets.UTF_8), "0".repeat(64)), Times.now());

        TrendPoint p = DiagnosticsTrend.of(run, upload.device().model(), 2);

        assertThat(p.measurement()).isEqualTo("diagnostics_run");
        assertThat(p.tags()).containsEntry("deviceSerial", upload.device().serial())
                .containsEntry("os", upload.os().type())
                .containsOnlyKeys("deviceSerial", "os", "deviceModel");
        assertThat(p.fields()).containsEntry("flag_count", 2L);
        if (upload.battery().cycleCount() != null) {
            assertThat(p.fields()).containsEntry("battery_cycle_count", upload.battery().cycleCount().longValue());
        }
        upload.benchmarks().forEach(b -> assertThat(p.fields())
                .containsEntry("bench_" + b.metric().toLowerCase(java.util.Locale.ROOT) + "_median", b.median()));
        assertThat(p.time()).isEqualTo(upload.collectedAt().toInstant());
    }
}
