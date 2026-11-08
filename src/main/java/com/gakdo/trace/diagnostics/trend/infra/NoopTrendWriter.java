package com.gakdo.trace.diagnostics.trend.infra;

import com.gakdo.trace.diagnostics.trend.application.TrendPoint;
import com.gakdo.trace.diagnostics.trend.application.TrendWriter;

/** INFLUX_URL이 비었을 때(학교 서버 Compose 등) 쓰는 빈 구현. 아무 것도 하지 않는다. */
public class NoopTrendWriter implements TrendWriter {

    @Override
    public void write(TrendPoint point) {
        // 추이 저장소가 없으면 쓰지 않는다
    }
}
