package com.gakdo.trace.diagnostics.trend.application;

/** 진단 실행 저장 트랜잭션 안에서 내는 애플리케이션 이벤트. 커밋된 뒤에만 추이를 쓴다(TrendListener). */
public record DiagnosticsTrendRecorded(TrendPoint point) {
}
