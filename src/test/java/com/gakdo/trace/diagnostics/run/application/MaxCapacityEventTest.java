// 이 파일이 확인하는 것: 진단 기록 이벤트에 실리는 "배터리 최대 용량(%)" 값의 범위 처리.

package com.gakdo.trace.diagnostics.run.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaxCapacityEventTest {

    // [상황] 배터리 최대 용량이 89.02%, 200%, 200.01%, 300%, 값 없음인 경우를 각각 이벤트로 바꾼다.
    // [기대] 0~200% 안의 값은 그대로 싣고, 200%를 넘는(비정상) 값이나 없는 값은 null(모름)로 싣는다.
    //        다른 서버가 받는 이벤트 형식(0~200%)을 깨지 않기 위함.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("이벤트의 배터리 최대 용량은 0~200%만 싣고, 200%를 넘거나 없으면 null로 싣는다")
    void capacityOutsideEventRangeIsNull() {
        // (도구) assertThat(값).isEqualTo(기대값) = "값이 기대값과 같아야 한다"를 확인하는 AssertJ 도구
        // 실행+확인: 89.02%는 범위 안이라 그대로 실려야 한다
        assertThat(DiagnosticRunService.forEvent(new BigDecimal("89.02"))).isEqualTo(89.02);
        // 실행+확인: 경계값 200%도 그대로 실려야 한다
        assertThat(DiagnosticRunService.forEvent(new BigDecimal("200"))).isEqualTo(200.0);
        // (도구) isNull() = 값이 없어야(null) 통과
        // 실행+확인: 200%를 조금이라도 넘으면 null이어야 한다
        assertThat(DiagnosticRunService.forEvent(new BigDecimal("200.01"))).isNull();
        // 실행+확인: 300%(비정상 값)도 null이어야 한다
        assertThat(DiagnosticRunService.forEvent(new BigDecimal("300.00"))).isNull();
        // 실행+확인: 값이 없으면 null이어야 한다
        assertThat(DiagnosticRunService.forEvent(null)).isNull();
    }
}
