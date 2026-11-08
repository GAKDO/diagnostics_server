package com.gakdo.trace.diagnostics.trend.infra;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.gakdo.trace.diagnostics.trend.application.TrendWriter;

/** INFLUX_URL이 있으면 HTTP로 쓰고, 비면 no-op(기본 꺼짐). 추이 쓰기는 가상 스레드에서 돈다. */
@Configuration
@EnableConfigurationProperties(InfluxProperties.class)
public class InfluxConfig {

    @Bean
    TrendWriter trendWriter(InfluxProperties props, RestClient.Builder builder) {
        return TrendWriters.create(props, builder);
    }

    @Bean(destroyMethod = "close")
    ExecutorService trendExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
