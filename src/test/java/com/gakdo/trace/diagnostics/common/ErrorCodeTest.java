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
