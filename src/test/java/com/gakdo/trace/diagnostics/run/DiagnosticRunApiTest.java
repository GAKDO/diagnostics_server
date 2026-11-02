package com.gakdo.trace.diagnostics.run;

import static com.gakdo.trace.diagnostics.support.TestJwts.OTHER_RENTAL;
import static com.gakdo.trace.diagnostics.support.TestJwts.OUTBOUND;
import static com.gakdo.trace.diagnostics.support.TestJwts.RENTAL;
import static com.gakdo.trace.diagnostics.support.TestJwts.RETURN;
import static com.gakdo.trace.diagnostics.support.TestJwts.admin;
import static com.gakdo.trace.diagnostics.support.TestJwts.agent;
import static com.gakdo.trace.diagnostics.support.TestJwts.MAC_SERIAL;
import static com.gakdo.trace.diagnostics.support.TestJwts.PC_SERIAL;
import static com.gakdo.trace.diagnostics.support.TestJwts.pcAgent;
import static com.gakdo.trace.diagnostics.support.TestJwts.booth;
import static com.gakdo.trace.diagnostics.support.TestJwts.capture;
import static com.gakdo.trace.diagnostics.support.TestJwts.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.diagnostics.common.infra.Times;
import com.gakdo.trace.diagnostics.run.api.DiagnosticRunController;
import com.gakdo.trace.diagnostics.run.application.DiagnosticRunService;
import com.gakdo.trace.diagnostics.run.application.DiagnosticsEventPublisher;
import com.gakdo.trace.diagnostics.run.application.DiagnosticsRecordedEvent;
import com.gakdo.trace.diagnostics.run.domain.DiagnosticRun;
import com.gakdo.trace.diagnostics.run.domain.DiagnosticUpload;
import com.gakdo.trace.diagnostics.run.infra.DiagnosticRunRepository;
import com.gakdo.trace.diagnostics.run.infra.InspectionFlagRepository;
import com.gakdo.trace.diagnostics.run.infra.NetworkntDiagnosticSchema;
import com.gakdo.trace.diagnostics.support.EventSchemas;
import com.gakdo.trace.diagnostics.support.Examples;
import com.gakdo.trace.diagnostics.support.WebTestBase;

/**
 * 웹 계층 + 서비스(실제 검증 규칙)를 저장소만 바꿔 끼워 시험한다. 실제 DB는 DiagnosticsIntegrationTest.
 * (이야기 1·2·3·4의 수용 시나리오)
 */
@WebMvcTest(controllers = DiagnosticRunController.class)
@Import({DiagnosticRunService.class, NetworkntDiagnosticSchema.class})
class DiagnosticRunApiTest extends WebTestBase {

    private static final String RUNS = "/api/v1/diagnostics/runs";
    private static final String KEY = "agent-key-0001";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DiagnosticRunRepository runs;
    @MockitoBean
    private InspectionFlagRepository flags;
    @MockitoBean
    private DiagnosticsEventPublisher events;
    @MockitoBean
    private PlatformTransactionManager txManager;

    private final AtomicLong ids = new AtomicLong();

    @BeforeEach
    void saveAssignsId() {
        when(runs.saveAndFlush(any(DiagnosticRun.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
    }

    private ResultActions upload(byte[] body, org.springframework.test.web.servlet.request.RequestPostProcessor token)
            throws Exception {
        return mvc.perform(post(RUNS).with(token).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---- 이야기 1: 진단 결과 올리기

    @Test
    @DisplayName("AGENT 토큰과 유효한 진단 JSON을 올리면 201, runId(NanoID)와 빈 flags")
    void uploadValidRun() throws Exception {
        upload(Examples.bytes(Examples.MACOS), agent())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").value(org.hamcrest.Matchers.matchesPattern("^[A-Za-z0-9_-]{21}$")))
                .andExpect(jsonPath("$.flags").isEmpty());
    }

    @Test
    @DisplayName("저장된 sha256은 요청 본문 바이트 그대로의 SHA-256이고 원본 본문도 함께 남는다")
    void storesRawBodyAndHash() throws Exception {
        byte[] body = Examples.bytes(Examples.MACOS);
        upload(body, agent()).andExpect(status().isCreated());
        ArgumentCaptor<DiagnosticRun> saved = ArgumentCaptor.forClass(DiagnosticRun.class);
        verify(runs).saveAndFlush(saved.capture());
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        assertThat(saved.getValue().sha256()).isEqualTo(expected);
        assertThat(saved.getValue().rawBody()).isEqualTo(new String(body, StandardCharsets.UTF_8));
        assertThat(saved.getValue().checkpointId()).isEqualTo(OUTBOUND);
        assertThat(saved.getValue().rentalId()).isEqualTo(RENTAL);
    }

    @Test
    @DisplayName("업로드하면 trace.diagnostics.recorded 이벤트 하나가 계약 스키마대로 나가고, 첫 진단은 baseRunId가 null")
    void publishesRecordedEvent() throws Exception {
        byte[] body = Examples.bytes(Examples.MACOS);
        String runId = runIdOf(upload(body, agent()).andExpect(status().isCreated()));
        DiagnosticsRecordedEvent event = publishedEvent();
        assertThat(event.runId()).isEqualTo(runId);
        assertThat(event.rentalId()).isEqualTo(RENTAL);
        assertThat(event.checkpointId()).isEqualTo(OUTBOUND);
        assertThat(event.baseRunId()).isNull();
        assertThat(event.flags()).isEmpty();
        assertThat(EventSchemas.validateRecorded(objectMapper.valueToTree(event))).isEmpty();
    }

    @Test
    @DisplayName("같은 렌탈에 앞선 진단이 있으면 이벤트 baseRunId는 바로 앞 실행의 runId")
    void baseRunIdIsPreviousRun() throws Exception {
        DiagnosticRun outbound = stored(OUTBOUND, RENTAL, Examples.MACOS, "outbound-key-01");
        when(runs.findFirstByRentalIdOrderByIdDesc(RENTAL)).thenReturn(Optional.of(outbound));
        upload(Examples.bytes(Examples.MACOS), agent(RETURN, RENTAL)).andExpect(status().isCreated());
        DiagnosticsRecordedEvent event = publishedEvent();
        assertThat(event.baseRunId()).isEqualTo(outbound.publicId());
        assertThat(EventSchemas.validateRecorded(objectMapper.valueToTree(event))).isEmpty();
    }

    @Test
    @DisplayName("스키마에 맞지 않는 결과는 422 DIAGNOSTIC_SCHEMA_INVALID로 거부되고 저장·이벤트가 없다")
    void schemaInvalidIsRejected() throws Exception {
        for (String name : List.of("invalid/benchmark-without-ac-power.json", "invalid/capacity-without-unit.json",
                "invalid/computer-name.json", "invalid/skipped-without-reason.json")) {
            upload(Examples.bytes(name), agent())
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("DIAGNOSTIC_SCHEMA_INVALID"))
                    .andExpect(jsonPath("$.errors[0].field").isNotEmpty());
        }
        verify(runs, never()).saveAndFlush(any());
        verify(events, never()).publishRecorded(any());
    }

    @Test
    @DisplayName("모르는 키 computerName은 필드 경로 device.computerName으로 알려 준다")
    void unknownKeyPath() throws Exception {
        upload(Examples.bytes("invalid/computer-name.json"), agent())
                .andExpect(jsonPath("$.errors[0].field").value("device.computerName"));
    }

    @Test
    @DisplayName("스키마는 통과해도 median이 가운데 값이 아니면 422 DIAGNOSTIC_SCHEMA_INVALID")
    void serverRuleViolationIsRejected() throws Exception {
        byte[] body = Examples.modified(Examples.MACOS,
                n -> ((com.fasterxml.jackson.databind.node.ObjectNode) n.get("benchmarks").get(0)).put("median", 1.5));
        upload(body, agent())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DIAGNOSTIC_SCHEMA_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("benchmarks[0].median"));
    }

    @Test
    @DisplayName("본문이 JSON이 아니면 400 VALIDATION_FAILED")
    void notJsonIsBadRequest() throws Exception {
        upload("{not json".getBytes(StandardCharsets.UTF_8), agent())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("SSD를 권한이 없어 건너뛰면 201이고 값은 null, 사유는 그대로 저장된다")
    void skippedStorageIsStoredWithReason() throws Exception {
        upload(Examples.bytes(Examples.SSD_SKIPPED), pcAgent()).andExpect(status().isCreated());
        ArgumentCaptor<DiagnosticRun> saved = ArgumentCaptor.forClass(DiagnosticRun.class);
        verify(runs).saveAndFlush(saved.capture());
        assertThat(saved.getValue().ssd().status()).isEqualTo("SKIPPED");
        assertThat(saved.getValue().ssd().skippedReason()).isEqualTo("NO_PERMISSION");
        assertThat(saved.getValue().ssd().percentUsed()).isNull();
    }

    @Test
    @DisplayName("전원 없이 벤치마크를 건너뛰면 사유 NO_AC_POWER가 저장된다")
    void benchmarkSkipReasonIsStored() throws Exception {
        byte[] body = Examples.modified(Examples.MACOS, n -> {
            ((com.fasterxml.jackson.databind.node.ObjectNode) n.get("conditions")).put("acPower", false);
            n.putArray("benchmarks");
            n.put("benchmarkSkippedReason", "NO_AC_POWER");
        });
        upload(body, agent()).andExpect(status().isCreated());
        ArgumentCaptor<DiagnosticRun> saved = ArgumentCaptor.forClass(DiagnosticRun.class);
        verify(runs).saveAndFlush(saved.capture());
        assertThat(saved.getValue().benchmarkSkippedReason()).isEqualTo("NO_AC_POWER");
        assertThat(saved.getValue().benchmarks()).isEmpty();
    }

    @Test
    @DisplayName("진단한 기기 시리얼이 AGENT 토큰의 등록 시리얼과 다르면 422 DEVICE_SERIAL_MISMATCH, 저장·이벤트 없음")
    void serialMismatchIsRejected() throws Exception {
        upload(Examples.bytes(Examples.WINDOWS), agent(OUTBOUND, RENTAL, MAC_SERIAL))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_SERIAL_MISMATCH"));
        verify(runs, never()).saveAndFlush(any());
        verify(events, never()).publishRecorded(any());
    }

    @Test
    @DisplayName("첫 진단이라도 등록 시리얼과 다르면 거부하고, 같으면 앞선 진단 없이도 저장한다")
    void serialIsCheckedAgainstClaimEvenOnFirstRun() throws Exception {
        upload(Examples.bytes(Examples.MACOS), agent(OUTBOUND, RENTAL, PC_SERIAL))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_SERIAL_MISMATCH"));
        upload(Examples.bytes(Examples.WINDOWS), pcAgent()).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("deviceSerial 클레임이 없는 AGENT 토큰(예전 페어링 코드)은 403 FORBIDDEN")
    void agentWithoutSerialIsForbidden() throws Exception {
        upload(Examples.bytes(Examples.MACOS), agent(OUTBOUND, RENTAL, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(runs, never()).saveAndFlush(any());
    }

    // ---- 이야기 2: 다시 보내기와 중복

    @Test
    @DisplayName("같은 키·같은 본문으로 다시 보내면 처음 응답(201, 같은 runId)이고 새 행·이벤트가 없다")
    void sameKeySameBodyReplays() throws Exception {
        DiagnosticRun existing = stored(OUTBOUND, RENTAL, Examples.MACOS, KEY);
        when(runs.findByCheckpointId(OUTBOUND)).thenReturn(Optional.of(existing));
        upload(Examples.bytes(Examples.MACOS), agent())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").value(existing.publicId()))
                .andExpect(jsonPath("$.flags").isEmpty());
        verify(runs, never()).saveAndFlush(any());
        verify(events, never()).publishRecorded(any());
    }

    @Test
    @DisplayName("같은 키에 다른 본문이면 422 IDEMPOTENCY_KEY_CONFLICT")
    void sameKeyOtherBodyConflicts() throws Exception {
        when(runs.findByCheckpointId(OUTBOUND)).thenReturn(Optional.of(stored(OUTBOUND, RENTAL, Examples.MACOS, KEY)));
        upload(Examples.bytes(Examples.SSD_SKIPPED), agent())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    @DisplayName("이미 진단이 있는 시점에 다른 키로 올리면 409 DIAGNOSTIC_ALREADY_UPLOADED")
    void secondUploadOnCheckpointConflicts() throws Exception {
        when(runs.findByCheckpointId(OUTBOUND))
                .thenReturn(Optional.of(stored(OUTBOUND, RENTAL, Examples.MACOS, "first-key-0001")));
        upload(Examples.bytes(Examples.MACOS), agent())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DIAGNOSTIC_ALREADY_UPLOADED"));
    }

    @Test
    @DisplayName("Idempotency-Key가 없거나 8자 미만이면 400 VALIDATION_FAILED")
    void idempotencyKeyRequired() throws Exception {
        mvc.perform(post(RUNS).with(agent()).contentType(MediaType.APPLICATION_JSON)
                .content(Examples.bytes(Examples.MACOS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(RUNS).with(agent()).header("Idempotency-Key", "short").contentType(MediaType.APPLICATION_JSON)
                .content(Examples.bytes(Examples.MACOS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- 이야기 3: 리포트용 시점 진단 조회

    @Test
    @DisplayName("출고 시점 조회: 배터리·SSD·부품·벤치마크 요약, previous는 null, flags는 빈 배열")
    void checkpointSummary() throws Exception {
        DiagnosticRun run = stored(OUTBOUND, RENTAL, Examples.MACOS, KEY);
        when(runs.findByCheckpointId(OUTBOUND)).thenReturn(Optional.of(run));
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpointId").value(OUTBOUND))
                .andExpect(jsonPath("$.current.runId").value(run.publicId()))
                .andExpect(jsonPath("$.current.os").value("MACOS"))
                .andExpect(jsonPath("$.current.battery.cycleCount").value(214))
                .andExpect(jsonPath("$.current.battery.designCapacity").value(4382))
                .andExpect(jsonPath("$.current.battery.fullChargeCapacity").value(3901))
                .andExpect(jsonPath("$.current.battery.capacityUnit").value("mAh"))
                .andExpect(jsonPath("$.current.battery.maxCapacityPct").value(89.02))
                .andExpect(jsonPath("$.current.battery.condition").value("NORMAL"))
                .andExpect(jsonPath("$.current.ssd.percentUsed").value(3))
                .andExpect(jsonPath("$.current.ssd.availableSparePct").value(100))
                .andExpect(jsonPath("$.current.ssd.skippedReason").doesNotExist())
                .andExpect(jsonPath("$.current.components[0].kind").value("CPU"))
                .andExpect(jsonPath("$.current.benchmarks[0].metric").value("CPU_SINGLE"))
                .andExpect(jsonPath("$.previous").doesNotExist())
                .andExpect(jsonPath("$.flags").isEmpty());
    }

    @Test
    @DisplayName("반납 시점 조회의 previous는 같은 렌탈 직전 시점 요약이다(두 번 측정한 값을 한 번의 조회로)")
    void checkpointWithPrevious() throws Exception {
        DiagnosticRun outbound = stored(OUTBOUND, RENTAL, Examples.SSD_SKIPPED, "outbound-key-01");
        DiagnosticRun returned = stored(RETURN, RENTAL, Examples.WINDOWS, KEY);
        when(runs.findByCheckpointId(RETURN)).thenReturn(Optional.of(returned));
        when(runs.findFirstByRentalIdAndIdLessThanOrderByIdDesc(eq(RENTAL), anyLong()))
                .thenReturn(Optional.of(outbound));
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + RETURN).with(customer(RENTAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.runId").value(returned.publicId()))
                .andExpect(jsonPath("$.current.battery.capacityUnit").value("mWh"))
                .andExpect(jsonPath("$.previous.runId").value(outbound.publicId()))
                .andExpect(jsonPath("$.previous.ssd.percentUsed").doesNotExist())
                .andExpect(jsonPath("$.previous.ssd.skippedReason").value("NO_PERMISSION"));
    }

    @Test
    @DisplayName("배터리가 없는 기기(NO_BATTERY)는 battery가 null")
    void noBatteryIsNull() throws Exception {
        DiagnosticRun run = stored(OUTBOUND, RENTAL, Examples.modified(Examples.WINDOWS, n -> {
            var b = (com.fasterxml.jackson.databind.node.ObjectNode) n.get("battery");
            b.put("status", "NO_BATTERY");
            b.putNull("skippedReason");
            b.putNull("cycleCount");
            b.putNull("designCapacity");
            b.putNull("fullChargeCapacity");
            b.putNull("osMaxCapacityPercent");
            b.putNull("osCondition");
        }), KEY);
        when(runs.findByCheckpointId(OUTBOUND)).thenReturn(Optional.of(run));
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.battery").doesNotExist());
    }

    @Test
    @DisplayName("배터리를 읽지 못했으면 battery 값은 null이고 skippedReason에 사유 코드가 나온다")
    void skippedBatteryShowsReason() throws Exception {
        DiagnosticRun run = stored(OUTBOUND, RENTAL, Examples.modified(Examples.WINDOWS, n -> {
            var b = (com.fasterxml.jackson.databind.node.ObjectNode) n.get("battery");
            b.put("status", "SKIPPED");
            b.put("skippedReason", "NO_PERMISSION");
            b.putNull("cycleCount");
            b.putNull("designCapacity");
            b.putNull("fullChargeCapacity");
            b.putNull("osMaxCapacityPercent");
            b.putNull("osCondition");
        }), KEY);
        when(runs.findByCheckpointId(OUTBOUND)).thenReturn(Optional.of(run));
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.battery.skippedReason").value("NO_PERMISSION"))
                .andExpect(jsonPath("$.current.battery.cycleCount").doesNotExist())
                .andExpect(jsonPath("$.current.battery.maxCapacityPct").doesNotExist());
    }

    @Test
    @DisplayName("다른 렌탈 고객은 403 OUT_OF_SCOPE, 촬영 JWT는 403 FORBIDDEN, 진단 없는 시점은 404")
    void checkpointScope() throws Exception {
        when(runs.findByCheckpointId(OUTBOUND)).thenReturn(Optional.of(stored(OUTBOUND, RENTAL, Examples.MACOS, KEY)));
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(customer(OTHER_RENTAL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(capture()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + RETURN).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/diagnostics/checkpoints/not-a-nanoid").with(admin()))
                .andExpect(status().isBadRequest());
    }

    // ---- 이야기 4: 범위 확인

    @Test
    @DisplayName("ADMIN·CUSTOMER·BOOTH 토큰으로 업로드하면 403, AGENT로 조회하면 403")
    void rolesAreChecked() throws Exception {
        upload(Examples.bytes(Examples.MACOS), admin()).andExpect(status().isForbidden());
        upload(Examples.bytes(Examples.MACOS), customer(RENTAL)).andExpect(status().isForbidden());
        upload(Examples.bytes(Examples.MACOS), booth()).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(agent()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("AGENT 토큰의 scope나 rentalId가 NanoID가 아니면 403 FORBIDDEN")
    void agentClaimsMustBePublicIds() throws Exception {
        upload(Examples.bytes(Examples.MACOS), agent("not-a-checkpoint", RENTAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        upload(Examples.bytes(Examples.MACOS), agent(OUTBOUND, "x"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("토큰이 없으면 401 UNAUTHENTICATED")
    void unauthenticated() throws Exception {
        mvc.perform(post(RUNS).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content(Examples.bytes(Examples.MACOS)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---- 도우미

    private String runIdOf(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("runId").asText();
    }

    private DiagnosticsRecordedEvent publishedEvent() {
        ArgumentCaptor<DiagnosticsRecordedEvent> captor = ArgumentCaptor.forClass(DiagnosticsRecordedEvent.class);
        verify(events).publishRecorded(captor.capture());
        return captor.getValue();
    }

    private DiagnosticRun stored(String checkpointId, String rentalId, String example, String key) throws Exception {
        return stored(checkpointId, rentalId, Examples.bytes(example), key);
    }

    private DiagnosticRun stored(String checkpointId, String rentalId, byte[] body, String key) throws Exception {
        DiagnosticUpload upload = objectMapper.readValue(body, DiagnosticUpload.class);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        return withId(DiagnosticRun.record(com.gakdo.trace.diagnostics.common.infra.PublicIds.next(), checkpointId,
                rentalId, key, upload, new String(body, StandardCharsets.UTF_8), sha, Times.now()));
    }

    private DiagnosticRun withId(DiagnosticRun run) throws Exception {
        Field id = DiagnosticRun.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(run, ids.incrementAndGet());
        return run;
    }

}
