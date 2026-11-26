// 이 파일이 확인하는 것: 형식(스키마) 검사를 통과한 진단 결과에 서버가 한 번 더 거는 업무 규칙.
// 값끼리 앞뒤가 맞는지(단위·중앙값·중복), DB 칸에 들어갈 크기인지, 배터리 효율 계산이 맞는지 본다.

package com.gakdo.trace.diagnostics.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gakdo.trace.diagnostics.common.domain.DomainException.FieldError;
import com.gakdo.trace.diagnostics.run.domain.DiagnosticUpload;
import com.gakdo.trace.diagnostics.run.domain.UploadRules;
import com.gakdo.trace.diagnostics.support.Examples;

class UploadRulesTest {

    // 테스트 도구: JSON 객체를 서버가 받는 업로드 객체(DiagnosticUpload)로 바꾼다
    private static DiagnosticUpload upload(ObjectNode node) throws Exception {
        // 날짜 등 읽기 도구를 붙여 JSON을 업로드 객체로 바꿔 돌려준다
        return Examples.JSON.findAndRegisterModules().treeToValue(node, DiagnosticUpload.class);
    }

    // [상황] contracts의 맥·윈도우·SSD 생략 예시를 규칙 검사에 넣는다.
    // [기대] 셋 다 위반이 없다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("contracts valid 예시는 서버 규칙도 통과한다")
    void validExamplesPassRules() throws Exception {
        // 맥·윈도우·SSD 생략 예시를 차례로
        for (String name : List.of(Examples.MACOS, Examples.WINDOWS, Examples.SSD_SKIPPED)) {
            // (도구) assertThat(값).isEmpty() = 비어 있어야 통과. as(이름) = 실패하면 어느 예시인지 보이게 이름표를 붙인다
            // 실행+확인: 규칙 검사 위반 목록이 비어 있어야 한다
            assertThat(UploadRules.check(upload(Examples.node(name)))).as(name).isEmpty();
        }
    }

    // [상황] 배터리 설계 용량은 mAh인데 현재 완충 용량만 mWh로 바꾼다.
    // [기대] 단위가 달라 비교할 수 없으므로 battery.fullChargeCapacity.unit 위반으로 거부된다.
    @Test
    @DisplayName("두 용량의 단위가 다르면 거부된다")
    void capacityUnitsMustMatch() throws Exception {
        // 준비: 맥 진단 예시를 읽는다
        ObjectNode node = Examples.node(Examples.MACOS);
        // 준비: 현재 완충 용량의 단위만 mWh로 바꾼다(설계 용량은 mAh)
        ((ObjectNode) node.get("battery").get("fullChargeCapacity")).put("unit", "mWh");
        // (도구) extracting(칸) = 목록에서 그 칸만 뽑는다. containsExactly = 정확히 그것만 있어야 통과
        // 실행+확인: 규칙 검사의 위반 위치가
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field)
                // battery.fullChargeCapacity.unit 하나여야 한다
                .containsExactly("battery.fullChargeCapacity.unit");
    }

    // [상황] 벤치마크(성능 측정)의 중앙값을 실제 측정값들의 가운데 값이 아닌 1.0으로 바꾼다.
    // [기대] 조작되거나 잘못 계산된 값으로 보고 benchmarks[1].median 위반으로 거부된다.
    @Test
    @DisplayName("median이 trials의 가운데 값이 아니면 거부된다")
    void medianMustBeMiddleTrial() throws Exception {
        // 준비: 맥 진단 예시를 읽는다
        ObjectNode node = Examples.node(Examples.MACOS);
        // 준비: 두 번째 벤치마크의 중앙값을 엉뚱한 1.0으로 바꾼다
        ((ObjectNode) node.get("benchmarks").get(1)).put("median", 1.0);
        // 실행+확인: 위반 위치가
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field)
                // benchmarks[1].median 하나여야 한다
                .containsExactly("benchmarks[1].median");
    }

    // [상황] 같은 성능 지표(예: CPU 단일 코어)가 두 번 들어 있다.
    // [기대] benchmarks[1].metric 위반으로 거부된다.
    @Test
    @DisplayName("같은 지표가 두 번 있으면 거부된다")
    void metricMustNotRepeat() throws Exception {
        // 준비: 맥 진단 예시를 읽는다
        ObjectNode node = Examples.node(Examples.MACOS);
        // 벤치마크 목록을 꺼낸다
        ArrayNode benchmarks = (ArrayNode) node.get("benchmarks");
        // 준비: 두 번째 벤치마크의 지표 이름을 첫 번째와 같게 바꾼다(중복)
        ((ObjectNode) benchmarks.get(1)).put("metric", benchmarks.get(0).get("metric").asText());
        // (도구) contains(값) = 그 값이 들어 있어야 통과
        // 실행+확인: 위반 위치에 benchmarks[1].metric이 있어야 한다
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field).contains("benchmarks[1].metric");
    }

    // [상황] SSD 사용 시간(powerOnHours)을 50억 시간처럼 터무니없이 큰 값으로 넣는다.
    // [기대] DB 칸 범위를 넘으므로 storage.powerOnHours 위반으로 거부된다.
    @Test
    @DisplayName("SSD 값이 int 범위를 넘으면 거부된다")
    void storageCountersFitInt() throws Exception {
        // 준비: 맥 진단 예시를 읽는다
        ObjectNode node = Examples.node(Examples.MACOS);
        // 준비: SSD 사용 시간을 50억 시간으로 바꾼다(정수 칸 범위 초과)
        ((ObjectNode) node.get("storage")).put("powerOnHours", 5_000_000_000L);
        // 실행+확인: 위반 위치가
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field)
                // storage.powerOnHours 하나여야 한다
                .containsExactly("storage.powerOnHours");
    }

    // [상황] 배터리 완충 용량을 설계 용량의 11배로 넣는다.
    // [기대] 현실적으로 불가능한 값이라 battery.fullChargeCapacity.value 위반으로 거부된다.
    @Test
    @DisplayName("완충 용량이 설계의 10배를 넘으면 거부된다")
    void capacityRatioFitsColumn() throws Exception {
        // 준비: 맥 진단 예시를 읽는다
        ObjectNode node = Examples.node(Examples.MACOS);
        // 준비: 완충 용량을 설계 용량(4382)의 11배로 바꾼다
        ((ObjectNode) node.get("battery").get("fullChargeCapacity")).put("value", 4382 * 11);
        // 실행+확인: 위반 위치가
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field)
                // battery.fullChargeCapacity.value 하나여야 한다
                .containsExactly("battery.fullChargeCapacity.value");
    }

    // [상황] 설계 4382mAh, 완충 3901mAh인 배터리.
    // [기대] 배터리 효율(최대 용량)은 3901 ÷ 4382 × 100 = 89.02%(소수 둘째 자리)로 계산된다.
    @Test
    @DisplayName("효율 %는 완충 ÷ 설계 × 100을 소수 둘째 자리로 계산한다")
    void maxCapacityPct() {
        // 준비: 설계 4382mAh·완충 3901mAh인 배터리 값을 만든다
        var battery = new DiagnosticUpload.Battery("OK", null, 214, new DiagnosticUpload.Capacity(4382, "mAh"),
                new DiagnosticUpload.Capacity(3901, "mAh"), 89, "NORMAL");
        // (도구) isEqualByComparingTo(값) = 숫자 크기가 같으면 통과(소수 자릿수 표기 차이는 무시)
        // 실행+확인: 효율이 89.02%로 계산돼야 한다
        assertThat(UploadRules.maxCapacityPct(battery)).isEqualByComparingTo(new BigDecimal("89.02"));
    }
}
