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
    @Test
    @DisplayName("서버가 내는 오류 code는 모두 contracts codes.yaml error_code에 있다")
    void errorCodesAreInContract() throws Exception {
        JsonNode codes;
        try (InputStream in = getClass().getResourceAsStream("/contracts/schemas/codes.gen.schema.json")) {
            codes = Examples.JSON.readTree(in);
        }
        List<String> contract = new ArrayList<>();
        codes.at("/$defs/ErrorCode/enum").forEach(n -> contract.add(n.asText()));
        assertThat(contract).containsAll(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }
}
