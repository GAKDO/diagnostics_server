package com.gakdo.trace.diagnostics.trend.infra;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 진단 추이 InfluxDB 설정(app.influx). url이 비면 추이를 쓰지 않는다(no-op).
 * 환경 변수 INFLUX_URL·INFLUX_TOKEN·INFLUX_ORG·INFLUX_BUCKET(기본 trace-pilot).
 */
@ConfigurationProperties(prefix = "app.influx")
public record InfluxProperties(String url, String token, String org, String bucket, Duration connectTimeout,
        Duration readTimeout) {

    public InfluxProperties {
        bucket = bucket == null || bucket.isBlank() ? "trace-pilot" : bucket;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
    }

    public boolean enabled() {
        return url != null && !url.isBlank();
    }
}
