package com.gakdo.trace.diagnostics.trend.infra;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.gakdo.trace.diagnostics.trend.application.TrendPoint;
import com.gakdo.trace.diagnostics.trend.application.TrendWriter;

/**
 * InfluxDB v2 HTTP API(POST /api/v2/write)에 line protocol을 직접 보낸다. 클라이언트 라이브러리는 쓰지 않는다.
 * 실패(연결·4xx·5xx)는 경고 로그만 남기고 예외를 밖으로 내지 않는다. 토큰은 로그에 남기지 않는다.
 */
public class InfluxHttpTrendWriter implements TrendWriter {

    private static final Logger log = LoggerFactory.getLogger(InfluxHttpTrendWriter.class);
    private static final MediaType LINE_PROTOCOL = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final RestClient client;
    private final InfluxProperties props;

    public InfluxHttpTrendWriter(RestClient.Builder builder, InfluxProperties props) {
        this.props = props;
        String base = props.url().endsWith("/") ? props.url().substring(0, props.url().length() - 1) : props.url();
        this.client = builder.baseUrl(base).build();
    }

    @Override
    public void write(TrendPoint point) {
        String line = LineProtocol.line(point);
        if (line == null) {
            return;
        }
        try {
            client.post()
                    .uri(u -> {
                        u.path("/api/v2/write").queryParam("bucket", props.bucket()).queryParam("precision", "ms");
                        if (props.org() != null && !props.org().isBlank()) {
                            u.queryParam("org", props.org());
                        }
                        return u.build();
                    })
                    .headers(h -> {
                        if (props.token() != null && !props.token().isBlank()) {
                            h.set(HttpHeaders.AUTHORIZATION, "Token " + props.token());
                        }
                    })
                    .contentType(LINE_PROTOCOL)
                    .body(line)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("InfluxDB에 진단 추이를 쓰지 못했습니다(bucket={}): {}", props.bucket(), e.getMessage());
        }
    }
}
