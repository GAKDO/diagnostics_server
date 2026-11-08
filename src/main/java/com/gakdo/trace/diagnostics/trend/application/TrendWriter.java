package com.gakdo.trace.diagnostics.trend.application;

/**
 * 진단 추이를 시계열 저장소(InfluxDB 버킷 trace-pilot)에 쓴다.
 * 구현은 실패해도 예외를 던지지 않고 경고 로그만 남긴다(업로드 API를 실패시키지 않는다).
 */
public interface TrendWriter {

    void write(TrendPoint point);
}
