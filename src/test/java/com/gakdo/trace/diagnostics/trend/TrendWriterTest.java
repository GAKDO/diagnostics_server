package com.gakdo.trace.diagnostics.trend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.gakdo.trace.diagnostics.trend.application.DiagnosticsTrendRecorded;
import com.gakdo.trace.diagnostics.trend.application.TrendListener;
import com.gakdo.trace.diagnostics.trend.application.TrendPoint;
import com.gakdo.trace.diagnostics.trend.application.TrendWriter;
import com.gakdo.trace.diagnostics.trend.infra.InfluxHttpTrendWriter;
import com.gakdo.trace.diagnostics.trend.infra.InfluxProperties;
import com.gakdo.trace.diagnostics.trend.infra.NoopTrendWriter;
import com.gakdo.trace.diagnostics.trend.infra.TrendWriters;

class TrendWriterTest {

    private static final Instant T = Instant.parse("2026-11-08T00:00:00Z");
    private static final TrendPoint POINT = TrendPoint.of("diagnostics_run", Map.of("os", "MACOS"),
            Map.of("flag_count", 0L), T);

    private static InfluxProperties props(String url) {
        return new InfluxProperties(url, "tok", "gakdo", null, Duration.ofMillis(200), Duration.ofMillis(200));
    }

    @Test
    @DisplayName("INFLUX_URL이 비면 아무 것도 하지 않는 no-op 빈을 쓰고, 버킷 기본값은 trace-pilot이다")
    void blankUrlIsNoop() {
        InfluxProperties blank = props("");
        assertThat(blank.enabled()).isFalse();
        assertThat(blank.bucket()).isEqualTo("trace-pilot");
        assertThat(props(null).enabled()).isFalse();
        TrendWriter writer = TrendWriters.create(blank, RestClient.builder());
        assertThat(writer).isInstanceOf(NoopTrendWriter.class);
        assertThatCode(() -> writer.write(POINT)).doesNotThrowAnyException();
        assertThat(TrendWriters.create(props("http://influx:8086"), RestClient.builder()))
                .isInstanceOf(InfluxHttpTrendWriter.class);
    }

    @Test
    @DisplayName("/api/v2/write에 org·bucket·precision=ms와 Token 헤더로 line protocol을 보낸다")
    void postsLineProtocol() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://influx:8086/api/v2/write?bucket=trace-pilot&precision=ms&org=gakdo"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Token tok"))
                .andExpect(content().string("diagnostics_run,os=MACOS flag_count=0i " + T.toEpochMilli()))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        new InfluxHttpTrendWriter(builder, props("http://influx:8086/")).write(POINT);
        server.verify();
    }

    @Test
    @DisplayName("InfluxDB가 5xx를 주거나 연결할 수 없어도 쓰기는 예외를 내지 않는다")
    void failureDoesNotThrow() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://influx:8086/api/v2/write")))
                .andRespond(withServerError());
        assertThatCode(() -> new InfluxHttpTrendWriter(builder, props("http://influx:8086")).write(POINT))
                .doesNotThrowAnyException();
        server.verify();
        // 열려 있지 않은 포트: 연결 거부
        TrendWriter unreachable = TrendWriters.create(props("http://127.0.0.1:1"), RestClient.builder());
        assertThatCode(() -> unreachable.write(POINT)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("커밋 뒤 리스너는 쓰기가 예외를 던지거나 실행기가 거절해도 예외를 내지 않는다")
    void listenerSwallowsFailures() {
        List<TrendPoint> written = new ArrayList<>();
        new TrendListener(written::add, Runnable::run).onRecorded(new DiagnosticsTrendRecorded(POINT));
        assertThat(written).containsExactly(POINT);

        TrendWriter failing = p -> {
            throw new IllegalStateException("boom");
        };
        assertThatCode(() -> new TrendListener(failing, Runnable::run).onRecorded(new DiagnosticsTrendRecorded(POINT)))
                .doesNotThrowAnyException();
        assertThatCode(() -> new TrendListener(written::add, r -> {
            throw new java.util.concurrent.RejectedExecutionException("closed");
        }).onRecorded(new DiagnosticsTrendRecorded(POINT))).doesNotThrowAnyException();
    }
}
