package com.gakdo.trace.diagnostics.common.domain;

import org.springframework.http.HttpStatus;

/**
 * 이 서버가 내는 오류 code. 값은 contracts codes.yaml {@code error_code}와 같아야 한다(복사가 아니라 이름 대조, ErrorCodeTest).
 * 제목은 화면이 그대로 보여 줄 수 있는 한국어 짧은 문장이다.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),
    OUT_OF_SCOPE(HttpStatus.FORBIDDEN, "이 렌탈의 진단을 볼 권한이 없습니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없습니다"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.UNPROCESSABLE_ENTITY, "같은 요청 키로 다른 요청을 보냈습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다"),
    DIAGNOSTIC_ALREADY_UPLOADED(HttpStatus.CONFLICT, "이 시점의 진단은 이미 올라갔습니다"),
    DIAGNOSTIC_SCHEMA_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "진단 결과 형식이 올바르지 않습니다"),
    DEVICE_SERIAL_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "렌탈 기기와 시리얼이 다릅니다");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
