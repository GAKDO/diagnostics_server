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
 *
 * <p>실행 조건: 진짜 PostgreSQL이 필요하다(바탕 클래스 PostgresTestBase).
 * GitHub Actions는 Docker(Testcontainers)로 띄우고, Docker가 없으면 {@code TRACE_IT_LOCAL=1}일 때 로컬 테스트 전용 DB
 * trace_it에 붙는다. 둘 다 없으면 이 클래스 전체를 건너뛴다(실패가 아님).
 */
// (도구) @EnabledIf = 괄호 안 메서드가 true일 때만 이 클래스를 실행한다(DB가 없으면 건너뛰기 위해)
@EnabledIf("com.gakdo.trace.diagnostics.support.PostgresTestBase#infrastructureAvailable")
// PostgresTestBase를 이어받아 진짜 PostgreSQL에 붙은 스프링 앱 위에서 테스트한다
class DiagnosticsIntegrationTest extends PostgresTestBase {

    // (도구) @Autowired = 스프링이 만들어 둔 객체를 이 칸에 넣어 준다
    // (도구) MockMvc = 서버를 실제로 띄우지 않고 HTTP 요청을 흉내 내 컨트롤러를 부르는 도구
    @Autowired
    private MockMvc mvc;
    // DB를 SQL로 직접 읽고 쓰려고 JdbcTemplate을 받는다(저장 결과를 표에서 바로 확인하기 위해)
    @Autowired
    private JdbcTemplate jdbc;
    // 응답 JSON을 읽어 runId를 꺼내려고 ObjectMapper(JSON 변환기)를 받는다
    @Autowired
    private ObjectMapper objectMapper;
    // (도구) @MockitoBean = 스프링 안의 진짜 객체를 가짜(mock)로 바꿔 끼운다. mock은 진짜 일은 안 하고 불린 기록만 남기는 대역이다
    // 이벤트 발행기를 가짜로 바꿔 메시지 큐 없이도 "이벤트를 냈는지"만 셀 수 있게 한다
    @MockitoBean
    private DiagnosticsEventPublisher events;

    // 테스트마다 진단 관련 표를 비우고 시작한다(앞 테스트의 데이터가 섞이지 않게).
    // (도구) @BeforeEach = 각 테스트가 시작되기 직전에 매번 먼저 실행된다
    @BeforeEach
    void clean() {
        // 진단 표 7개를 한 번에 비운다(CASCADE = 이 표를 가리키는 다른 표의 행까지 함께 지움)
        jdbc.execute("TRUNCATE diagnostics.inspection_flag, diagnostics.benchmark_result, diagnostics.ssd_health, "
                + "diagnostics.battery_reading, diagnostics.hardware_component, diagnostics.diagnostic_run, "
                + "diagnostics.sealed_checkpoint CASCADE");
    }

    private ResultActions upload(byte[] body, RequestPostProcessor token, String key) throws Exception {
        // (도구) mvc.perform(...) = 만든 요청을 컨트롤러에 보내고, 결과(응답)를 확인할 수 있는 객체를 돌려준다
        // 테스트용 토큰과 요청 키(Idempotency-Key)를 붙여 진단 JSON을 올리기 API로 보낸다(같은 업로드 코드를 테스트마다 반복하지 않으려고)
        return mvc.perform(post("/api/v1/diagnostics/runs").with(token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // [상황] 윈도우 노트북 에이전트가 SSD 검사를 권한 문제로 건너뛴 진단을 올린다.
    // [기대] 실행·부품·배터리·SSD·벤치마크(5개) 행으로 나뉘어 저장되고, 원본 JSON과 그 해시(SHA-256)가 그대로 남는다.
    //        SSD는 "건너뜀(NO_PERMISSION)" 사유와 함께 값 없이 저장되고, 이벤트는 한 번 나간다.
    @Test
    @DisplayName("올린 진단은 실행·부품·배터리·SSD·벤치마크 행으로 저장되고 원본·해시·생략 사유가 남는다")
    void uploadIsStored() throws Exception {
        // 준비: SSD 검사를 건너뛴 윈도우 진단 예시를 바이트로 읽는다(올린 원본과 해시를 나중에 비교하려고)
        byte[] body = Examples.bytes(Examples.SSD_SKIPPED);
        // 실행: 윈도우 노트북 에이전트 토큰으로 올리고 201(새로 만듦)인지 확인한다
        // (도구) andExpect(...) = 응답이 기대와 같은지 확인하고, 다르면 테스트를 실패시킨다
        upload(body, pcAgent(), "key-00000001").andExpect(status().isCreated());

        // 확인: 저장된 진단 실행 행 하나를 칸 이름 → 값 묶음으로 읽는다(무엇이 저장됐는지 직접 보려고)
        Map<String, Object> run = jdbc.queryForMap("SELECT * FROM diagnostics.diagnostic_run");
        // 올린 원본의 SHA-256 해시를 테스트에서 직접 계산한다(서버가 저장한 해시와 맞는지 비교하려고)
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        // (도구) assertThat(값) = "이 값이 ~여야 한다"를 확인하는 도구. 뒤에 붙는 조건이 틀리면 테스트가 실패한다
        // (도구) containsEntry(칸, 값) = 묶음 안에 그 칸이 그 값으로 들어 있는지 확인한다
        assertThat(run)
                // 출고 시점 ID로 저장됐는지 확인한다(어느 시점의 진단인지 잃지 않게)
                .containsEntry("checkpoint_id", OUTBOUND)
                // 토큰에 담긴 렌탈 ID로 저장됐는지 확인한다
                .containsEntry("rental_id", RENTAL)
                // 진단한 기기의 시리얼이 저장됐는지 확인한다
                .containsEntry("device_serial", "PF3TEST01")
                // 운영체제가 WINDOWS로 저장됐는지 확인한다
                .containsEntry("os", "WINDOWS")
                // 서버가 저장한 해시가 테스트에서 계산한 해시와 같은지 확인한다(원본이 바뀌지 않았음을 증명하려고)
                .containsEntry("sha256", sha256)
                // 올린 JSON 원문이 글자 하나 안 바뀌고 그대로 저장됐는지 확인한다
                .containsEntry("raw_body", new String(body, java.nio.charset.StandardCharsets.UTF_8));
        // 부품 표의 행 수를 세어
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnostics.hardware_component", Integer.class))
                // 한 개 이상인지 확인한다(부품 정보가 따로 나뉘어 저장됐는지 보려고)
                .isPositive();
        // 벤치마크 표의 행 수를 세어
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnostics.benchmark_result", Integer.class))
                // 예시에 든 벤치마크 5개가 모두 저장됐는지 확인한다
                .isEqualTo(5);
        // SSD 상태 행을 읽는다(건너뛴 검사가 어떻게 저장됐는지 보려고)
        Map<String, Object> ssd = jdbc.queryForMap("SELECT * FROM diagnostics.ssd_health");
        assertThat(ssd)
                // SSD 상태가 "건너뜀"으로 저장됐는지 확인한다
                .containsEntry("status", "SKIPPED")
                // 건너뛴 사유가 "권한 없음"으로 남았는지 확인한다
                .containsEntry("skipped_reason", "NO_PERMISSION")
                // 읽지 못한 사용량은 지어내지 않고 빈 값(null)인지 확인한다
                .containsEntry("percent_used", null);
        // 배터리 행을 읽는다
        Map<String, Object> battery = jdbc.queryForMap("SELECT * FROM diagnostics.battery_reading");
        // 윈도우가 준 용량 단위 mWh가 그대로 저장됐는지 확인한다
        assertThat(battery).containsEntry("capacity_unit", "mWh");
        // 최대 용량 비율(%)이 계산돼 채워졌는지 확인한다
        assertThat(battery.get("max_capacity_pct")).isNotNull();
        // (도구) verify(가짜, times(1)).메서드(any()) = 가짜 객체의 그 메서드가 인자와 상관없이(any) 정확히 1번 불렸는지 확인한다
        // "진단 기록됨" 이벤트가 한 번만 나갔는지 확인한다
        verify(events, times(1)).publishRecorded(any());
    }

    // [상황] 같은 요청 키로 두 번 보내고(네트워크 재시도), 다른 키로 같은 시점에 또 보낸다.
    // [기대] 재시도는 같은 runId를 돌려주고, 다른 키는 409(이미 올림). DB에는 진단 한 건, 이벤트도 한 번뿐.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("같은 키로 다시 보내면 같은 runId, 다른 키로 같은 시점이면 409, 행은 하나")
    void replayAndConflictOnRealDatabase() throws Exception {
        // 준비: 맥 진단 예시를 바이트로 읽는다
        byte[] body = Examples.bytes(Examples.MACOS);
        // 실행: 첫 번째로 올리고 돌려받은 runId를 적어 둔다
        String first = runId(upload(body, agent(), "key-00000001").andExpect(status().isCreated()));
        // 같은 요청 키로 한 번 더 올린다(네트워크 재시도 흉내)
        String again = runId(upload(body, agent(), "key-00000001").andExpect(status().isCreated()));
        // 확인: 재시도는 새로 만들지 않고 처음과 같은 runId를 돌려주는지 확인한다
        assertThat(again).isEqualTo(first);
        // 다른 키로 같은 시점에 올리면 409(이미 올림)인지 확인한다
        upload(body, agent(), "key-00000002").andExpect(status().isConflict())
                // (도구) jsonPath("$.code") = 응답 JSON에서 code 칸을 가리킨다. value(...)로 그 값이 맞는지 본다
                // 오류 코드가 "이미 올림"인지 확인한다
                .andExpect(jsonPath("$.code").value("DIAGNOSTIC_ALREADY_UPLOADED"));
        // DB에 진단이 한 건만 있는지 확인한다(중복 저장 방지)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnostics.diagnostic_run", Integer.class)).isEqualTo(1);
        // 이벤트도 한 번만 나갔는지 확인한다
        verify(events, times(1)).publishRecorded(any());
    }

    // [상황] 출고 진단을 올린 뒤 관리자가 "진단 다시 하기"를 하고, 새 키로 다시 올린다.
    // [기대] 다시 하기 직후 조회는 404(지금 진단 없음). 새로 올린 진단이 그 시점의 결과가 되고,
    //        이전 진단은 지우지 않고 행으로 남는다(DB에 2건, 이벤트 2번).
    //        ※ 응답 코드: 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("진단 다시 하기 뒤 새 키로 올리면 새 진단이 그 시점의 결과가 되고, 이전 진단은 행으로 남는다")
    void redoKeepsHistoryOnRealDatabase() throws Exception {
        // 준비: 맥 진단 예시를 바이트로 읽는다
        byte[] body = Examples.bytes(Examples.MACOS);
        // 출고 진단을 한 번 올리고 runId를 적어 둔다
        String first = runId(upload(body, agent(), "key-00000001").andExpect(status().isCreated()));
        // 실행: 관리자 토큰으로 출고 시점 "진단 다시 하기"를 부른다
        mvc.perform(post("/api/v1/diagnostics/checkpoints/" + OUTBOUND + "/redo").with(admin()))
                // 확인: 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // 밀려난 진단이 방금 올린 첫 진단인지 확인한다
                .andExpect(jsonPath("$.supersededRunId").value(first));
        // 다시 하기 직후 그 시점을 조회하면 404(지금 진단 없음)인지 확인한다
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin())).andExpect(status().isNotFound());
        // 새 키로 다시 올리고 새 runId를 적어 둔다
        String second = runId(upload(body, agent(), "key-00000002").andExpect(status().isCreated()));
        // 새 진단은 이전 진단과 다른 runId인지 확인한다
        assertThat(second).isNotEqualTo(first);
        // 출고 시점을 다시 조회한다
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin()))
                // 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // 지금 진단(current)이 새로 올린 진단인지 확인한다
                .andExpect(jsonPath("$.current.runId").value(second))
                // 출고 시점은 앞 시점이 없으니 previous가 비어 있는지(nullValue) 확인한다
                .andExpect(jsonPath("$.previous").value(org.hamcrest.Matchers.nullValue()));
        // 출고 시점 진단이 DB에 2건인지 확인한다(이전 진단을 지우지 않고 남겼는지)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnostics.diagnostic_run WHERE checkpoint_id = ?",
                Integer.class, OUTBOUND)).isEqualTo(2);
        // 올린 두 번 모두 이벤트가 나갔는지(2번) 확인한다
        verify(events, times(2)).publishRecorded(any());
    }

    // [상황] 출고 진단이 봉인된 상태에서 관리자가 "진단 다시 하기"를 누른다.
    // [기대] 409 RECORD_ALREADY_SEALED. 봉인된 기록은 바꿀 수 없으므로 지금 진단이 그대로다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("봉인된 시점은 진단 다시 하기가 409이고 지금 진단은 그대로다")
    void sealedBlocksRedoOnRealDatabase() throws Exception {
        // 준비: 맥 출고 진단을 올리고 runId를 적어 둔다
        String first = runId(upload(Examples.bytes(Examples.MACOS), agent(), "key-00000001").andExpect(status().isCreated()));
        // 출고 시점을 봉인된 상태로 DB에 직접 넣는다(봉인 이벤트를 기다리지 않고 상황을 만들려고)
        jdbc.update("INSERT INTO diagnostics.sealed_checkpoint(checkpoint_id, sealed_at) VALUES (?, now())", OUTBOUND);
        // 실행: 관리자가 봉인된 시점에 "진단 다시 하기"를 부른다
        mvc.perform(post("/api/v1/diagnostics/checkpoints/" + OUTBOUND + "/redo").with(admin()))
                // 확인: 409(지금 상태와 맞지 않음)로 거절되는지 확인한다
                .andExpect(status().isConflict())
                // 오류 코드가 "이미 봉인됨"인지 확인한다
                .andExpect(jsonPath("$.code").value("RECORD_ALREADY_SEALED"));
        // 출고 시점을 다시 조회해
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin()))
                // 지금 진단이 바뀌지 않고 처음 진단 그대로인지 확인한다
                .andExpect(jsonPath("$.current.runId").value(first));
    }

    // [상황] 출고 때 배터리 89.02%, 반납 때 84.0%(충전 횟수 230)로 두 시점을 올리고, 고객이 반납 시점을 조회한다.
    // [기대] 한 번의 조회로 반납(current)과 출고(previous)의 배터리·SSD 값이 함께 오고,
    //        5.02%p 하락이라 CAPACITY_DROP 플래그 하나가 출고 진단을 기준으로 붙는다. 출고 조회는 previous가 없다.
    @Test
    @DisplayName("출고·반납 두 시점을 올리면 반납 조회 한 번으로 두 시점의 배터리·SSD와 용량 하락 플래그를 받는다")
    void twoCheckpointsInOneQuery() throws Exception {
        // 준비: 출고 시점 토큰으로 맥 진단(배터리 89.02%)을 올리고 runId를 적어 둔다
        String outbound = runId(upload(Examples.bytes(Examples.MACOS), agent(OUTBOUND, RENTAL), "key-00000001")
                .andExpect(status().isCreated()));
        // 같은 맥 예시를 복사해 반납 때 값으로 고친 진단을 만든다(배터리가 닳은 상황을 만들려고)
        byte[] returned = Examples.modified(Examples.MACOS, n -> {
            // JSON에서 battery 칸을 꺼낸다(안의 값을 고치기 위해)
            var battery = (com.fasterxml.jackson.databind.node.ObjectNode) n.get("battery");
            // 완충 용량을 3681로 낮춘다(최대 용량이 84.0%가 되게)
            ((com.fasterxml.jackson.databind.node.ObjectNode) battery.get("fullChargeCapacity")).put("value", 3681);
            // 충전 횟수를 230으로 바꾼다
            battery.put("cycleCount", 230);
        });
        // 반납 시점 토큰으로 고친 진단을 올리고 runId를 적어 둔다
        String ret = runId(upload(returned, agent(RETURN, RENTAL), "key-00000002").andExpect(status().isCreated()));

        // 실행: 고객 토큰으로 반납 시점을 조회한다
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + RETURN).with(customer(RENTAL)))
                // 확인: 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // 지금 진단(current)이 반납 진단인지 확인한다
                .andExpect(jsonPath("$.current.runId").value(ret))
                // 반납 때 충전 횟수 230이 보이는지 확인한다
                .andExpect(jsonPath("$.current.battery.cycleCount").value(230))
                // 반납 때 최대 용량 84.0%가 보이는지 확인한다
                .andExpect(jsonPath("$.current.battery.maxCapacityPct").value(84.0))
                // 앞 시점(previous)이 출고 진단인지 확인한다(한 번 조회로 두 시점을 받는지)
                .andExpect(jsonPath("$.previous.runId").value(outbound))
                // 출고 때 최대 용량 89.02%가 함께 오는지 확인한다
                .andExpect(jsonPath("$.previous.battery.maxCapacityPct").value(89.02))
                // 출고 때 SSD 사용량 3%도 함께 오는지 확인한다
                .andExpect(jsonPath("$.previous.ssd.percentUsed").value(3))
                // 89.02% → 84.0%는 5.02%p 하락이라 flag-rules-v1의 CAPACITY_DROP(5%p 이상)에 걸린다
                // 점검 플래그가 딱 하나인지 확인한다
                .andExpect(jsonPath("$.flags.length()").value(1))
                // 그 플래그가 "용량 하락"인지 확인한다
                .andExpect(jsonPath("$.flags[0].kind").value("CAPACITY_DROP"))
                // 플래그의 비교 기준이 출고 진단인지 확인한다
                .andExpect(jsonPath("$.flags[0].baseRunId").value(outbound));
        // 관리자로 출고 시점도 조회한다
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin()))
                // 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // 출고는 첫 시점이라 previous가 비어 있는지 확인한다
                .andExpect(jsonPath("$.previous").value(org.hamcrest.Matchers.nullValue()));
    }

    // [상황] 출고는 맥으로 진단했는데, 반납 때는 다른 기기(윈도우 노트북)를 진단해 올린다.
    // [기대] 422 DEVICE_SERIAL_MISMATCH(빌린 기기가 아님)로 거부되고 저장되지 않는다.
    //        ※ 응답 코드: 422 = 형식은 맞지만 내용이 규칙에 어긋남
    @Test
    @DisplayName("반납 시점에 등록 시리얼과 다른 기기를 진단하면 422 DEVICE_SERIAL_MISMATCH")
    void serialMismatchOnRealDatabase() throws Exception {
        // 준비: 출고 때 맥 진단을 올려 렌탈 기기를 정해 둔다
        upload(Examples.bytes(Examples.MACOS), agent(OUTBOUND, RENTAL), "key-00000001").andExpect(status().isCreated());
        // 실행: 반납 때는 맥 시리얼 토큰으로 윈도우 노트북 진단을 올린다(다른 기기를 진단한 상황)
        upload(Examples.bytes(Examples.WINDOWS), agent(RETURN, RENTAL, MAC_SERIAL), "key-00000002")
                // 확인: 422(내용이 규칙에 어긋남)로 거부되는지 확인한다
                .andExpect(status().isUnprocessableEntity())
                // 오류 코드가 "시리얼 불일치"인지 확인한다
                .andExpect(jsonPath("$.code").value("DEVICE_SERIAL_MISMATCH"));
        // DB에는 출고 진단 한 건만 있는지 확인한다(거부된 반납 진단은 저장되지 않음)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM diagnostics.diagnostic_run", Integer.class)).isEqualTo(1);
    }

    // 테스트 도구: 업로드 응답 JSON에서 runId를 꺼낸다
    private String runId(ResultActions result) throws Exception {
        // 응답 본문 글자를 JSON으로 읽어 runId 칸의 값을 글자로 돌려준다(다음 요청·비교에 쓰려고)
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("runId").asText();
    }
}
