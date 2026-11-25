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

    private static DiagnosticUpload upload(ObjectNode node) throws Exception {
        return Examples.JSON.findAndRegisterModules().treeToValue(node, DiagnosticUpload.class);
    }

    // [상황] contracts의 맥·윈도우·SSD 생략 예시를 규칙 검사에 넣는다.
    // [기대] 셋 다 위반이 없다.
    @Test
    @DisplayName("contracts valid 예시는 서버 규칙도 통과한다")
    void validExamplesPassRules() throws Exception {
        for (String name : List.of(Examples.MACOS, Examples.WINDOWS, Examples.SSD_SKIPPED)) {
            assertThat(UploadRules.check(upload(Examples.node(name)))).as(name).isEmpty();
        }
    }

    // [상황] 배터리 설계 용량은 mAh인데 현재 완충 용량만 mWh로 바꾼다.
    // [기대] 단위가 달라 비교할 수 없으므로 battery.fullChargeCapacity.unit 위반으로 거부된다.
    @Test
    @DisplayName("두 용량의 단위가 다르면 거부된다")
    void capacityUnitsMustMatch() throws Exception {
        ObjectNode node = Examples.node(Examples.MACOS);
        ((ObjectNode) node.get("battery").get("fullChargeCapacity")).put("unit", "mWh");
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field)
                .containsExactly("battery.fullChargeCapacity.unit");
    }

    // [상황] 벤치마크(성능 측정)의 중앙값을 실제 측정값들의 가운데 값이 아닌 1.0으로 바꾼다.
    // [기대] 조작되거나 잘못 계산된 값으로 보고 benchmarks[1].median 위반으로 거부된다.
    @Test
    @DisplayName("median이 trials의 가운데 값이 아니면 거부된다")
    void medianMustBeMiddleTrial() throws Exception {
        ObjectNode node = Examples.node(Examples.MACOS);
        ((ObjectNode) node.get("benchmarks").get(1)).put("median", 1.0);
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field)
                .containsExactly("benchmarks[1].median");
    }

    // [상황] 같은 성능 지표(예: CPU 단일 코어)가 두 번 들어 있다.
    // [기대] benchmarks[1].metric 위반으로 거부된다.
    @Test
    @DisplayName("같은 지표가 두 번 있으면 거부된다")
    void metricMustNotRepeat() throws Exception {
        ObjectNode node = Examples.node(Examples.MACOS);
        ArrayNode benchmarks = (ArrayNode) node.get("benchmarks");
        ((ObjectNode) benchmarks.get(1)).put("metric", benchmarks.get(0).get("metric").asText());
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field).contains("benchmarks[1].metric");
    }

    // [상황] SSD 사용 시간(powerOnHours)을 50억 시간처럼 터무니없이 큰 값으로 넣는다.
    // [기대] DB 칸 범위를 넘으므로 storage.powerOnHours 위반으로 거부된다.
    @Test
    @DisplayName("SSD 값이 int 범위를 넘으면 거부된다")
    void storageCountersFitInt() throws Exception {
        ObjectNode node = Examples.node(Examples.MACOS);
        ((ObjectNode) node.get("storage")).put("powerOnHours", 5_000_000_000L);
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field)
                .containsExactly("storage.powerOnHours");
    }

    // [상황] 배터리 완충 용량을 설계 용량의 11배로 넣는다.
    // [기대] 현실적으로 불가능한 값이라 battery.fullChargeCapacity.value 위반으로 거부된다.
    @Test
    @DisplayName("완충 용량이 설계의 10배를 넘으면 거부된다")
    void capacityRatioFitsColumn() throws Exception {
        ObjectNode node = Examples.node(Examples.MACOS);
        ((ObjectNode) node.get("battery").get("fullChargeCapacity")).put("value", 4382 * 11);
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field)
                .containsExactly("battery.fullChargeCapacity.value");
    }

    // [상황] 설계 4382mAh, 완충 3901mAh인 배터리.
    // [기대] 배터리 효율(최대 용량)은 3901 ÷ 4382 × 100 = 89.02%(소수 둘째 자리)로 계산된다.
    @Test
    @DisplayName("효율 %는 완충 ÷ 설계 × 100을 소수 둘째 자리로 계산한다")
    void maxCapacityPct() {
        var battery = new DiagnosticUpload.Battery("OK", null, 214, new DiagnosticUpload.Capacity(4382, "mAh"),
                new DiagnosticUpload.Capacity(3901, "mAh"), 89, "NORMAL");
        assertThat(UploadRules.maxCapacityPct(battery)).isEqualByComparingTo(new BigDecimal("89.02"));
    }
}
