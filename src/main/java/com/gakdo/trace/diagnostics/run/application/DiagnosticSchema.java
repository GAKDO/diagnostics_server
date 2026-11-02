package com.gakdo.trace.diagnostics.run.application;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.gakdo.trace.diagnostics.common.domain.DomainException.FieldError;

/** contracts 진단 결과 JSON Schema(diag.v1) 검증. 통과하면 빈 목록. */
public interface DiagnosticSchema {

    List<FieldError> validate(JsonNode document);
}
