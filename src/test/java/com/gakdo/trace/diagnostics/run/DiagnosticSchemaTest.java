package com.gakdo.trace.diagnostics.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.gakdo.trace.diagnostics.common.domain.DomainException.FieldError;
import com.gakdo.trace.diagnostics.run.infra.NetworkntDiagnosticSchema;
import com.gakdo.trace.diagnostics.support.Examples;

/** contracts 예시 7개를 그대로 검증한다(SC-001). */
class DiagnosticSchemaTest {

    private final NetworkntDiagnosticSchema schema = new NetworkntDiagnosticSchema();

    @ParameterizedTest
    @ValueSource(strings = {"valid/macos.json", "valid/windows.json", "valid/ssd-skipped.json", "valid/macos-checks.json",
            "valid/windows-checks-unknown.json"})
    @DisplayName("contracts valid 예시는 diag.v1 스키마를 통과한다")
    void validExamplesPass(String name) {
        assertThat(schema.validate(Examples.node(name))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid/benchmark-without-ac-power.json", "invalid/capacity-without-unit.json",
            "invalid/computer-name.json", "invalid/skipped-without-reason.json", "invalid/checks-bad-value.json"})
    @DisplayName("contracts invalid 예시는 스키마에 맞지 않아 거부된다")
    void invalidExamplesFail(String name) {
        assertThat(schema.validate(Examples.node(name))).isNotEmpty();
    }

    @Test
    @DisplayName("모르는 키(컴퓨터 이름)는 그 키 경로로 알려 준다")
    void unknownKeyIsReportedWithPath() {
        List<FieldError> errors = schema.validate(Examples.node("invalid/computer-name.json"));
        assertThat(errors).extracting(FieldError::field).anyMatch(f -> f.contains("computerName")
                || f.contains("device"));
    }

    @Test
    @DisplayName("빠진 필수 키는 키 이름으로 알려 준다")
    void missingRequiredKeyIsReported() {
        var node = Examples.node(Examples.MACOS);
        node.remove("consentedAt");
        assertThat(schema.validate(node)).extracting(FieldError::field).contains("consentedAt");
    }

    @Test
    @DisplayName("정수여야 하는 값이 글자면 그 필드 경로로 알려 준다")
    void wrongTypeIsReportedWithPath() {
        var node = Examples.node(Examples.MACOS);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("battery")).put("cycleCount", "많음");
        assertThat(schema.validate(node)).extracting(FieldError::field).contains("battery.cycleCount");
    }
}
