package com.gakdo.trace.diagnostics.trend.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.gakdo.trace.diagnostics.trend.application.TrendWriter;

/** 설정에 맞는 TrendWriter를 고른다. INFLUX_URL이 비면 no-op, 있으면 짧은 시간 제한을 둔 HTTP 쓰기. */
public final class TrendWriters {

    private static final Logger log = LoggerFactory.getLogger(TrendWriters.class);

    private TrendWriters() {
    }

    public static TrendWriter create(InfluxProperties props, RestClient.Builder builder) {
        if (!props.enabled()) {
            log.info("INFLUX_URL이 비어 진단 추이를 쓰지 않습니다.");
            return new NoopTrendWriter();
        }
        log.info("진단 추이를 InfluxDB에 씁니다: bucket={}", props.bucket());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        return new InfluxHttpTrendWriter(builder.clone().requestFactory(factory), props);
    }
}
