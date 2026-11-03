package com.gakdo.trace.diagnostics;

import static com.gakdo.trace.diagnostics.support.TestJwts.OUTBOUND;
import static com.gakdo.trace.diagnostics.support.TestJwts.RENTAL;
import static com.gakdo.trace.diagnostics.support.TestJwts.RETURN;
import static com.gakdo.trace.diagnostics.support.TestJwts.admin;
import static com.gakdo.trace.diagnostics.support.TestJwts.agent;
import static com.gakdo.trace.diagnostics.support.TestJwts.MAC_SERIAL;
import static com.gakdo.trace.diagnostics.support.TestJwts.pcAgent;
import static com.gakdo.trace.diagnostics.support.TestJwts.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.diagnostics.run.application.DiagnosticsEventPublisher;
import com.gakdo.trace.diagnostics.support.Examples;
import com.gakdo.trace.diagnostics.support.PostgresTestBase;

/**
 * 실제 PostgreSQL에 Flyway V1·V2를 올리고(엔티티와 표가 맞는지 validate) 업로드 → 저장 → 조회를 끝까지 확인한다.
 * 이벤트 발행은 바꿔 끼우고, RabbitMQ까지는 DiagnosticsEventIntegrationTest가 본다.
 */
@EnabledIf("com.gakdo.trace.diagnostics.support.PostgresTestBase#infrastructureAvailable")
class DiagnosticsIntegrationTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private DiagnosticsEventPublisher events;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE diagnostics.inspection_flag, diagnostics.benchmark_result, diagnostics.ssd_health, "
                + "diagnostics.battery_reading, diagnostics.hardware_component, diagnostics.diagnostic_run CASCADE");
    }

    private ResultActions upload(byte[] body, RequestPostProcessor token, String key) throws Exception {
        return mvc.perform(post("/api/v1/diagnostics/runs").with(token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("올린 진단은 실행·부품·배터리·SSD·벤치마크 행으로 저장되고 원본·해시·생략 사유가 남는다")
    void uploadIsStored() throws Exception {
        byte[] body = Examples.bytes(Examples.SSD_SKIPPED);
        upload(body, pcAgent(), "key-00000001").andExpect(status().isCreated());

        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM diagnostics.diagnostic_run");
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        assertThat(run)
                .containsEntry("checkpoint_id", OUTBOUND)
                .containsEntry("rental_id", RENTAL)
                .containsEntry("device_serial", "PF3TEST01")
                .containsEntry("os", "WINDOWS")
                .containsEntry("sha256", sha256)
                .containsEntry("raw_body", new String(body, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnostics.hardware_component", Integer.class))
                .isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnostics.benchmark_result", Integer.class))
                .isEqualTo(5);
        Map<String, Object> ssd = jdbc.queryForMap("SELECT * FROM diagnostics.ssd_health");
        assertThat(ssd)
                .containsEntry("status", "SKIPPED")
                .containsEntry("skipped_reason", "NO_PERMISSION")
                .containsEntry("percent_used", null);
        Map<String, Object> battery = jdbc.queryForMap("SELECT * FROM diagnostics.battery_reading");
        assertThat(battery).containsEntry("capacity_unit", "mWh");
        assertThat(battery.get("max_capacity_pct")).isNotNull();
        verify(events, times(1)).publishRecorded(any());
    }

    @Test
    @DisplayName("같은 키로 다시 보내면 같은 runId, 다른 키로 같은 시점이면 409, 행은 하나")
    void replayAndConflictOnRealDatabase() throws Exception {
        byte[] body = Examples.bytes(Examples.MACOS);
        String first = runId(upload(body, agent(), "key-00000001").andExpect(status().isCreated()));
        String again = runId(upload(body, agent(), "key-00000001").andExpect(status().isCreated()));
        assertThat(again).isEqualTo(first);
        upload(body, agent(), "key-00000002").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIAGNOSTIC_ALREADY_UPLOADED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnostics.diagnostic_run", Integer.class)).isEqualTo(1);
        verify(events, times(1)).publishRecorded(any());
    }

    @Test
    @DisplayName("출고·반납 두 시점을 올리면 반납 조회 한 번으로 두 시점의 배터리·SSD를 받는다")
    void twoCheckpointsInOneQuery() throws Exception {
        String outbound = runId(upload(Examples.bytes(Examples.MACOS), agent(OUTBOUND, RENTAL), "key-00000001")
                .andExpect(status().isCreated()));
        byte[] returned = Examples.modified(Examples.MACOS, n -> {
            var battery = (com.fasterxml.jackson.databind.node.ObjectNode) n.get("battery");
            ((com.fasterxml.jackson.databind.node.ObjectNode) battery.get("fullChargeCapacity")).put("value", 3681);
            battery.put("cycleCount", 230);
        });
        String ret = runId(upload(returned, agent(RETURN, RENTAL), "key-00000002").andExpect(status().isCreated()));

        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + RETURN).with(customer(RENTAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.runId").value(ret))
                .andExpect(jsonPath("$.current.battery.cycleCount").value(230))
                .andExpect(jsonPath("$.current.battery.maxCapacityPct").value(84.0))
                .andExpect(jsonPath("$.previous.runId").value(outbound))
                .andExpect(jsonPath("$.previous.battery.maxCapacityPct").value(89.02))
                .andExpect(jsonPath("$.previous.ssd.percentUsed").value(3))
                .andExpect(jsonPath("$.flags").isEmpty());
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previous").doesNotExist());
    }

    @Test
    @DisplayName("반납 시점에 등록 시리얼과 다른 기기를 진단하면 422 DEVICE_SERIAL_MISMATCH")
    void serialMismatchOnRealDatabase() throws Exception {
        upload(Examples.bytes(Examples.MACOS), agent(OUTBOUND, RENTAL), "key-00000001").andExpect(status().isCreated());
        upload(Examples.bytes(Examples.WINDOWS), agent(RETURN, RENTAL, MAC_SERIAL), "key-00000002")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_SERIAL_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnostics.diagnostic_run", Integer.class)).isEqualTo(1);
    }

    private String runId(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("runId").asText();
    }
}
