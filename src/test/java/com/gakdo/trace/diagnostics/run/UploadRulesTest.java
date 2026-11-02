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

    @Test
    @DisplayName("contracts valid 예시는 서버 규칙도 통과한다")
    void validExamplesPassRules() throws Exception {
        for (String name : List.of(Examples.MACOS, Examples.WINDOWS, Examples.SSD_SKIPPED)) {
            assertThat(UploadRules.check(upload(Examples.node(name)))).as(name).isEmpty();
        }
    }

    @Test
    @DisplayName("두 용량의 단위가 다르면 거부된다")
    void capacityUnitsMustMatch() throws Exception {
        ObjectNode node = Examples.node(Examples.MACOS);
        ((ObjectNode) node.get("battery").get("fullChargeCapacity")).put("unit", "mWh");
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field)
                .containsExactly("battery.fullChargeCapacity.unit");
    }

    @Test
    @DisplayName("median이 trials의 가운데 값이 아니면 거부된다")
    void medianMustBeMiddleTrial() throws Exception {
        ObjectNode node = Examples.node(Examples.MACOS);
        ((ObjectNode) node.get("benchmarks").get(1)).put("median", 1.0);
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field)
                .containsExactly("benchmarks[1].median");
    }

    @Test
    @DisplayName("같은 지표가 두 번 있으면 거부된다")
    void metricMustNotRepeat() throws Exception {
        ObjectNode node = Examples.node(Examples.MACOS);
        ArrayNode benchmarks = (ArrayNode) node.get("benchmarks");
        ((ObjectNode) benchmarks.get(1)).put("metric", benchmarks.get(0).get("metric").asText());
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field).contains("benchmarks[1].metric");
    }

    @Test
    @DisplayName("SSD 값이 int 범위를 넘으면 거부된다")
    void storageCountersFitInt() throws Exception {
        ObjectNode node = Examples.node(Examples.MACOS);
        ((ObjectNode) node.get("storage")).put("powerOnHours", 5_000_000_000L);
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field)
                .containsExactly("storage.powerOnHours");
    }

    @Test
    @DisplayName("완충 용량이 설계의 10배를 넘으면 거부된다")
    void capacityRatioFitsColumn() throws Exception {
        ObjectNode node = Examples.node(Examples.MACOS);
        ((ObjectNode) node.get("battery").get("fullChargeCapacity")).put("value", 4382 * 11);
        assertThat(UploadRules.check(upload(node))).extracting(FieldError::field)
                .containsExactly("battery.fullChargeCapacity.value");
    }

    @Test
    @DisplayName("효율 %는 완충 ÷ 설계 × 100을 소수 둘째 자리로 계산한다")
    void maxCapacityPct() {
        var battery = new DiagnosticUpload.Battery("OK", null, 214, new DiagnosticUpload.Capacity(4382, "mAh"),
                new DiagnosticUpload.Capacity(3901, "mAh"), 89, "NORMAL");
        assertThat(UploadRules.maxCapacityPct(battery)).isEqualByComparingTo(new BigDecimal("89.02"));
    }
}
