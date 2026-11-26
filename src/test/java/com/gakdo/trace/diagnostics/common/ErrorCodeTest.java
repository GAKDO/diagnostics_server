// 이 파일이 확인하는 것: 진단 서버가 돌려주는 오류 코드가 팀 공통 약속(contracts의 codes.yaml)에 모두 들어 있는지.

package com.gakdo.trace.diagnostics.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.gakdo.trace.diagnostics.common.domain.ErrorCode;
import com.gakdo.trace.diagnostics.support.Examples;

class ErrorCodeTest {

    // [상황] 서버 코드에 정의된 오류 코드 목록과 contracts의 error_code 목록을 비교한다.
    // [기대] 서버 쪽 오류 코드가 전부 계약 목록 안에 있다. 웹 화면이 모르는 코드를 받는 일이 없게 한다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("서버가 내는 오류 code는 모두 contracts codes.yaml error_code에 있다")
    void errorCodesAreInContract() throws Exception {
        // 준비: 계약 코드 목록을 담을 자리
        JsonNode codes;
        // 준비: contracts에서 만든 코드 스키마 사본 파일을 연다(다 읽으면 자동으로 닫힘)
        try (InputStream in = getClass().getResourceAsStream("/contracts/schemas/codes.gen.schema.json")) {
            // 파일을 JSON으로 읽는다
            codes = Examples.JSON.readTree(in);
        }
        // 계약의 오류 코드 이름을 모을 빈 목록
        List<String> contract = new ArrayList<>();
        // 실행: 스키마의 ErrorCode 값 목록을 하나씩 꺼내 목록에 담는다
        codes.at("/$defs/ErrorCode/enum").forEach(n -> contract.add(n.asText()));
        // (도구) assertThat(목록).containsAll(다른 목록) = 다른 목록의 값이 모두 들어 있어야 통과
        // 확인: 서버의 오류 코드(ErrorCode) 이름이 전부 계약 목록 안에 있어야 한다
        assertThat(contract).containsAll(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }
}
