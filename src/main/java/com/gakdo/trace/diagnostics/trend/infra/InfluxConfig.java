package com.gakdo.trace.diagnostics.trend.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.gakdo.trace.diagnostics.trend.application.TrendWriter;

/** INFLUX_URL이 있으면 HTTP로 쓰고, 비면 no-op(기본 꺼짐). 추이 쓰기 실행기는 TrendListener가 직접 가진다(Executor 빈을 만들면 Boot의 applicationTaskExecutor 자동 구성이 꺼지므로). */
@Configuration
@EnableConfigurationProperties(InfluxProperties.class)
public class InfluxConfig {

    @Bean
    TrendWriter trendWriter(InfluxProperties props, RestClient.Builder builder) {
        return TrendWriters.create(props, builder);
    }
}
