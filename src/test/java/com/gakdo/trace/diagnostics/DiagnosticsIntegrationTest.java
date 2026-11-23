// 이 파일이 확인하는 것: 진짜 PostgreSQL DB에 표를 만들어 놓고, 진단 올리기 → 저장 → 조회를 끝까지 확인한다.
// 이벤트 발행은 가짜로 바꿔 끼우고(메시지 큐까지는 DiagnosticsEventIntegrationTest가 봄), DB가 없으면 건너뛴다.
// 시점: OUTBOUND = 출고 시점, RETURN = 반납 시점.

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

    // 테스트마다 진단 관련 표를 비우고 시작한다(앞 테스트의 데이터가 섞이지 않게).
    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE diagnostics.inspection_flag, diagnostics.benchmark_result, diagnostics.ssd_health, "
                + "diagnostics.battery_reading, diagnostics.hardware_component, diagnostics.diagnostic_run, "
                + "diagnostics.sealed_checkpoint CASCADE");
    }

    private ResultActions upload(byte[] body, RequestPostProcessor token, String key) throws Exception {
        return mvc.perform(post("/api/v1/diagnostics/runs").with(token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // [상황] 윈도우 노트북 에이전트가 SSD 검사를 권한 문제로 건너뛴 진단을 올린다.
    // [기대] 실행·부품·배터리·SSD·벤치마크(5개) 행으로 나뉘어 저장되고, 원본 JSON과 그 해시(SHA-256)가 그대로 남는다.
    //        SSD는 "건너뜀(NO_PERMISSION)" 사유와 함께 값 없이 저장되고, 이벤트는 한 번 나간다.
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

    // [상황] 같은 요청 키로 두 번 보내고(네트워크 재시도), 다른 키로 같은 시점에 또 보낸다.
    // [기대] 재시도는 같은 runId를 돌려주고, 다른 키는 409(이미 올림). DB에는 진단 한 건, 이벤트도 한 번뿐.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
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

    // [상황] 출고 진단을 올린 뒤 관리자가 "진단 다시 하기"를 하고, 새 키로 다시 올린다.
    // [기대] 다시 하기 직후 조회는 404(지금 진단 없음). 새로 올린 진단이 그 시점의 결과가 되고,
    //        이전 진단은 지우지 않고 행으로 남는다(DB에 2건, 이벤트 2번).
    //        ※ 응답 코드: 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("진단 다시 하기 뒤 새 키로 올리면 새 진단이 그 시점의 결과가 되고, 이전 진단은 행으로 남는다")
    void redoKeepsHistoryOnRealDatabase() throws Exception {
        byte[] body = Examples.bytes(Examples.MACOS);
        String first = runId(upload(body, agent(), "key-00000001").andExpect(status().isCreated()));
        mvc.perform(post("/api/v1/diagnostics/checkpoints/" + OUTBOUND + "/redo").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supersededRunId").value(first));
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin())).andExpect(status().isNotFound());
        String second = runId(upload(body, agent(), "key-00000002").andExpect(status().isCreated()));
        assertThat(second).isNotEqualTo(first);
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.runId").value(second))
                .andExpect(jsonPath("$.previous").value(org.hamcrest.Matchers.nullValue()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnostics.diagnostic_run WHERE checkpoint_id = ?",
                Integer.class, OUTBOUND)).isEqualTo(2);
        verify(events, times(2)).publishRecorded(any());
    }

    // [상황] 출고 진단이 봉인된 상태에서 관리자가 "진단 다시 하기"를 누른다.
    // [기대] 409 RECORD_ALREADY_SEALED. 봉인된 기록은 바꿀 수 없으므로 지금 진단이 그대로다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("봉인된 시점은 진단 다시 하기가 409이고 지금 진단은 그대로다")
    void sealedBlocksRedoOnRealDatabase() throws Exception {
        String first = runId(upload(Examples.bytes(Examples.MACOS), agent(), "key-00000001").andExpect(status().isCreated()));
        jdbc.update("INSERT INTO diagnostics.sealed_checkpoint(checkpoint_id, sealed_at) VALUES (?, now())", OUTBOUND);
        mvc.perform(post("/api/v1/diagnostics/checkpoints/" + OUTBOUND + "/redo").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_ALREADY_SEALED"));
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin()))
                .andExpect(jsonPath("$.current.runId").value(first));
    }

    // [상황] 출고 때 배터리 89.02%, 반납 때 84.0%(충전 횟수 230)로 두 시점을 올리고, 고객이 반납 시점을 조회한다.
    // [기대] 한 번의 조회로 반납(current)과 출고(previous)의 배터리·SSD 값이 함께 오고,
    //        5.02%p 하락이라 CAPACITY_DROP 플래그 하나가 출고 진단을 기준으로 붙는다. 출고 조회는 previous가 없다.
    @Test
    @DisplayName("출고·반납 두 시점을 올리면 반납 조회 한 번으로 두 시점의 배터리·SSD와 용량 하락 플래그를 받는다")
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
                // 89.02% → 84.0%는 5.02%p 하락이라 flag-rules-v1의 CAPACITY_DROP(5%p 이상)에 걸린다
                .andExpect(jsonPath("$.flags.length()").value(1))
                .andExpect(jsonPath("$.flags[0].kind").value("CAPACITY_DROP"))
                .andExpect(jsonPath("$.flags[0].baseRunId").value(outbound));
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previous").value(org.hamcrest.Matchers.nullValue()));
    }

    // [상황] 출고는 맥으로 진단했는데, 반납 때는 다른 기기(윈도우 노트북)를 진단해 올린다.
    // [기대] 422 DEVICE_SERIAL_MISMATCH(빌린 기기가 아님)로 거부되고 저장되지 않는다.
    //        ※ 응답 코드: 422 = 형식은 맞지만 내용이 규칙에 어긋남
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
