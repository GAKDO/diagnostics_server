// 이 파일이 확인하는 것: 고객 노트북 에이전트가 올리는 진단 결과 JSON이 약속한 형식(diag.v1 스키마)에 맞는지 검사하는 기능.
// contracts 레포의 올바른 예시는 통과하고, 잘못된 예시는 거부되며, 무엇이 틀렸는지 필드 위치로 알려 주는지 본다.

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

    // 시험할 진단 JSON 스키마 검사기(서버가 실제로 쓰는 것)
    private final NetworkntDiagnosticSchema schema = new NetworkntDiagnosticSchema();

    // [상황] contracts의 올바른 예시 7개(맥·윈도우·SSD 생략·잠금/보안 점검 포함 등)를 하나씩 검사한다.
    // [기대] 모두 오류 없이 통과한다.
    // (도구) @ParameterizedTest + @ValueSource = 같은 테스트를 아래 값(파일 이름)마다 한 번씩 돌린다. @DisplayName = 결과 화면에 보일 이름
    @ParameterizedTest
    @ValueSource(strings = {"valid/macos.json", "valid/windows.json", "valid/ssd-skipped.json", "valid/macos-checks.json",
            "valid/windows-checks-unknown.json", "valid/windows-security.json", "valid/macos-security.json"})
    @DisplayName("contracts valid 예시는 diag.v1 스키마를 통과한다")
    void validExamplesPass(String name) {
        // (도구) assertThat(목록).isEmpty() = 목록이 비어 있어야 통과하는 AssertJ 검사
        // 실행+확인: 올바른 예시를 검사하면 오류 목록이 비어 있어야 한다
        assertThat(schema.validate(Examples.node(name))).isEmpty();
    }

    // [상황] contracts의 잘못된 예시 6개(전원 없이 벤치마크, 용량 단위 없음, 컴퓨터 이름 포함, 생략 사유 없음,
    //        점검 값 오류, 위협 건수 음수)를 검사한다.
    // [기대] 모두 오류가 나서 거부된다.
    // 파일 이름마다 한 번씩 돌린다
    @ParameterizedTest
    @ValueSource(strings = {"invalid/benchmark-without-ac-power.json", "invalid/capacity-without-unit.json",
            "invalid/computer-name.json", "invalid/skipped-without-reason.json", "invalid/checks-bad-value.json",
            "invalid/threat-count-negative.json"})
    @DisplayName("contracts invalid 예시는 스키마에 맞지 않아 거부된다")
    void invalidExamplesFail(String name) {
        // (도구) isNotEmpty() = 비어 있지 않아야 통과
        // 실행+확인: 잘못된 예시를 검사하면 오류가 하나 이상 나와야 한다
        assertThat(schema.validate(Examples.node(name))).isNotEmpty();
    }

    // [상황] 개인정보인 컴퓨터 이름(computerName)이 들어간 진단을 검사한다.
    // [기대] 오류에 그 키의 위치가 나온다(약속에 없는 키는 받지 않음).
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시
    @Test
    @DisplayName("모르는 키(컴퓨터 이름)는 그 키 경로로 알려 준다")
    void unknownKeyIsReportedWithPath() {
        // 실행: 컴퓨터 이름이 들어간 예시를 검사해 오류 목록을 받는다
        List<FieldError> errors = schema.validate(Examples.node("invalid/computer-name.json"));
        // (도구) extracting(칸) = 목록에서 그 칸만 뽑는다. anyMatch(조건) = 하나라도 맞으면 통과
        // 확인: 오류 위치 중 하나에 computerName(또는 그 칸이 든 device)이 나와야 한다
        assertThat(errors).extracting(FieldError::field).anyMatch(f -> f.contains("computerName")
                || f.contains("device"));
    }

    // [상황] 꼭 있어야 하는 "고객 동의 시각(consentedAt)"을 뺀다.
    // [기대] 오류에 consentedAt 이름이 나온다.
    @Test
    @DisplayName("빠진 필수 키는 키 이름으로 알려 준다")
    void missingRequiredKeyIsReported() {
        // 준비: 맥 진단 예시를 읽는다
        var node = Examples.node(Examples.MACOS);
        // 준비: 필수 칸인 고객 동의 시각을 지운다
        node.remove("consentedAt");
        // (도구) contains(값) = 그 값이 들어 있어야 통과
        // 실행+확인: 검사하면 오류 위치에 consentedAt이 나와야 한다
        assertThat(schema.validate(node)).extracting(FieldError::field).contains("consentedAt");
    }

    // [상황] 배터리 충전 횟수(cycleCount)에 숫자 대신 "많음"이라는 글자를 넣는다.
    // [기대] 오류에 battery.cycleCount 위치가 나온다.
    @Test
    @DisplayName("정수여야 하는 값이 글자면 그 필드 경로로 알려 준다")
    void wrongTypeIsReportedWithPath() {
        // 준비: 맥 진단 예시를 읽는다
        var node = Examples.node(Examples.MACOS);
        // 준비: 배터리 충전 횟수에 숫자 대신 "많음"을 넣는다
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("battery")).put("cycleCount", "많음");
        // 실행+확인: 검사하면 오류 위치에 battery.cycleCount가 나와야 한다
        assertThat(schema.validate(node)).extracting(FieldError::field).contains("battery.cycleCount");
    }
}
