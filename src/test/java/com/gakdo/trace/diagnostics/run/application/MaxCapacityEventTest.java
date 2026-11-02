package com.gakdo.trace.diagnostics.run.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaxCapacityEventTest {

    @Test
    @DisplayName("이벤트의 배터리 최대 용량은 0~200%만 싣고, 200%를 넘거나 없으면 null로 싣는다")
    void capacityOutsideEventRangeIsNull() {
        assertThat(DiagnosticRunService.forEvent(new BigDecimal("89.02"))).isEqualTo(89.02);
        assertThat(DiagnosticRunService.forEvent(new BigDecimal("200"))).isEqualTo(200.0);
        assertThat(DiagnosticRunService.forEvent(new BigDecimal("200.01"))).isNull();
        assertThat(DiagnosticRunService.forEvent(new BigDecimal("300.00"))).isNull();
        assertThat(DiagnosticRunService.forEvent(null)).isNull();
    }
}
