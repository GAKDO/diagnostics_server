// 이 파일이 확인하는 것: 진단 서버 API 전체(올리기·다시 보내기·조회·권한·진단 다시 하기)를 화면 요청 수준에서 확인.
// 실제 검증 규칙은 그대로 쓰고 DB 저장소만 가짜로 바꿔 끼워 Docker 없이 빠르게 돈다.
// 진단 흐름: 고객 노트북의 에이전트가 6자리 페어링 코드로 AGENT 토큰을 받아 진단 결과(JSON)를 올리고,
// 관리자·고객은 렌탈 리포트에서 시점별(출고 OUTBOUND, 반납 RETURN) 진단 요약을 본다.

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
import com.gakdo.trace.diagnostics.run.application.SealedCheckpointStore;
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
// (도구) @WebMvcTest = 서버 전체가 아니라 웹 요청을 받는 컨트롤러 부분만 띄워 빠르게 시험한다
@WebMvcTest(controllers = DiagnosticRunController.class)
// 진짜 검증 규칙(서비스)과 진짜 JSON 형식 검사기를 함께 올린다(규칙은 가짜 없이 그대로 시험하려고)
@Import({DiagnosticRunService.class, NetworkntDiagnosticSchema.class})
// WebTestBase를 이어받아 테스트용 토큰 검사 설정을 함께 쓴다
class DiagnosticRunApiTest extends WebTestBase {

    // 진단 올리기 주소를 상수로 둔다(여러 테스트에서 같은 주소를 쓰려고)
    private static final String RUNS = "/api/v1/diagnostics/runs";
    // 요청 키(Idempotency-Key)로 쓸 고정 값이다(같은 요청인지 서버가 알아보는 표시)
    private static final String KEY = "agent-key-0001";

    // (도구) @Autowired = 스프링이 만들어 둔 객체를 이 칸에 넣어 준다
    // (도구) MockMvc = 서버를 실제로 띄우지 않고 HTTP 요청을 흉내 내 컨트롤러를 부르는 도구
    @Autowired
    private MockMvc mvc;
    // 나간 이벤트를 JSON으로 바꿔 형식 검사를 하려고 ObjectMapper(JSON 변환기)를 받는다
    @Autowired
    private ObjectMapper objectMapper;

    // (도구) @MockitoBean = 스프링 안의 진짜 객체를 가짜(mock)로 바꿔 끼운다. mock은 진짜 일은 안 하고 정해 준 대로만 답하는 대역이다
    // 진단 저장소를 가짜로 바꿔 DB 없이 저장·조회 결과를 마음대로 정한다
    @MockitoBean
    private DiagnosticRunRepository runs;
    // 점검 플래그 저장소도 가짜로 바꾼다
    @MockitoBean
    private InspectionFlagRepository flags;
    // 이벤트 발행기를 가짜로 바꿔 메시지 큐 없이 "무엇을 보냈는지"만 본다
    @MockitoBean
    private DiagnosticsEventPublisher events;
    // 트랜잭션 관리자도 가짜로 바꾼다(진짜 DB 연결 없이 서비스가 돌게)
    @MockitoBean
    private PlatformTransactionManager txManager;
    // 봉인 여부 저장소도 가짜로 바꿔 봉인 상황을 테스트에서 정한다
    @MockitoBean
    private SealedCheckpointStore seals;

    // 가짜 저장소가 붙여 줄 내부 번호를 1씩 늘려 가며 만드는 숫자 세기 도구다
    private final AtomicLong ids = new AtomicLong();

    // 테스트마다: 가짜 저장소가 저장할 때 DB처럼 내부 번호를 붙여 주도록 준비한다.
    // (도구) @BeforeEach = 각 테스트가 시작되기 직전에 매번 먼저 실행된다
    @BeforeEach
    void saveAssignsId() {
        // (도구) when(가짜.메서드(...)).thenAnswer(...) = 그 메서드가 불리면 이렇게 계산해 답하라고 정한다. any(...)는 "어떤 값이 오든"이라는 뜻
        // 저장 요청이 오면 받은 진단에 내부 번호를 붙여 그대로 돌려준다(DB가 저장할 때처럼 보이게)
        when(runs.saveAndFlush(any(DiagnosticRun.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
    }

    // 테스트 도구: 정해 둔 토큰과 요청 키로 진단 JSON을 올린다
    private ResultActions upload(byte[] body, org.springframework.test.web.servlet.request.RequestPostProcessor token)
            throws Exception {
        // (도구) mvc.perform(...) = 만든 요청을 컨트롤러에 보내고, 결과(응답)를 확인할 수 있는 객체를 돌려준다
        // 토큰·요청 키·JSON 형식을 붙여 올리기 API로 보낸다(업로드 코드를 테스트마다 반복하지 않으려고)
        return mvc.perform(post(RUNS).with(token).header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---- 이야기 1: 진단 결과 올리기

    // [상황] AGENT 토큰으로 올바른 맥 진단 결과를 올린다.
    // [기대] 201, 새 진단 ID(21자 NanoID)와 빈 플래그 목록이 온다.
    //        ※ 응답 코드: 201 = 새로 만들어짐
    @Test
    @DisplayName("AGENT 토큰과 유효한 진단 JSON을 올리면 201, runId(NanoID)와 빈 flags")
    void uploadValidRun() throws Exception {
        // 실행: 에이전트 토큰으로 맥 진단 예시를 올린다
        upload(Examples.bytes(Examples.MACOS), agent())
                // (도구) andExpect(...) = 응답이 기대와 같은지 확인하고, 다르면 테스트를 실패시킨다
                // 확인: 201(새로 만듦)인지 확인한다
                .andExpect(status().isCreated())
                // (도구) jsonPath("$.runId") = 응답 JSON에서 runId 칸을 가리킨다. value(...)로 그 값이 맞는지 본다
                // runId가 21자 NanoID 모양(영문·숫자·_·-)인지 정규식으로 확인한다
                .andExpect(jsonPath("$.runId").value(org.hamcrest.Matchers.matchesPattern("^[A-Za-z0-9_-]{21}$")))
                // 정상 진단이라 점검 플래그가 비어 있는지 확인한다
                .andExpect(jsonPath("$.flags").isEmpty());
    }

    // [상황] 진단 결과를 올린다.
    // [기대] 받은 본문 그대로의 SHA-256 해시와 원본 본문이 함께 저장된다(나중에 위변조 여부를 확인할 근거).
    @Test
    @DisplayName("저장된 sha256은 요청 본문 바이트 그대로의 SHA-256이고 원본 본문도 함께 남는다")
    void storesRawBodyAndHash() throws Exception {
        // 준비: 맥 진단 예시를 바이트로 읽는다(올린 원본과 저장된 값을 비교하려고)
        byte[] body = Examples.bytes(Examples.MACOS);
        // 실행: 올리고 201인지 확인한다
        upload(body, agent()).andExpect(status().isCreated());
        // (도구) ArgumentCaptor = 가짜 객체에 넘어간 값을 붙잡아 두는 도구. 나중에 getValue()로 꺼내 본다
        // 확인: 저장소에 넘어간 진단을 붙잡을 준비를 한다
        ArgumentCaptor<DiagnosticRun> saved = ArgumentCaptor.forClass(DiagnosticRun.class);
        // (도구) verify(가짜).메서드(...) = 그 메서드가 불렸는지 확인한다. capture()는 그때 넘어간 값을 붙잡는다
        // 저장이 한 번 불렸는지 확인하고, 넘어간 진단을 붙잡는다
        verify(runs).saveAndFlush(saved.capture());
        // 올린 원본의 SHA-256 해시를 테스트에서 직접 계산한다
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        // (도구) assertThat(값) = "이 값이 ~여야 한다"를 확인하는 도구. 뒤에 붙는 조건이 틀리면 테스트가 실패한다
        // 저장된 해시가 직접 계산한 해시와 같은지 확인한다(원본이 바뀌지 않았음을 증명하려고)
        assertThat(saved.getValue().sha256()).isEqualTo(expected);
        // 원본 JSON이 글자 그대로 저장됐는지 확인한다
        assertThat(saved.getValue().rawBody()).isEqualTo(new String(body, StandardCharsets.UTF_8));
        // 토큰에 담긴 출고 시점 ID로 저장됐는지 확인한다
        assertThat(saved.getValue().checkpointId()).isEqualTo(OUTBOUND);
        // 토큰에 담긴 렌탈 ID로 저장됐는지 확인한다
        assertThat(saved.getValue().rentalId()).isEqualTo(RENTAL);
    }

    // [상황] 첫 진단을 올린다.
    // [기대] "진단이 기록됨" 이벤트가 약속한 형식으로 한 번 나가고, 비교 기준(baseRunId)은 null,
    //        배터리 최대 용량은 89.02%로 실린다(리포트 서버가 받아 배터리 칸에 씀).
    @Test
    @DisplayName("업로드하면 trace.diagnostics.recorded 이벤트 하나가 계약 스키마대로 나가고, 첫 진단은 baseRunId가 null")
    void publishesRecordedEvent() throws Exception {
        // 준비: 맥 진단 예시를 바이트로 읽는다
        byte[] body = Examples.bytes(Examples.MACOS);
        // 실행: 올리고 응답에서 runId를 꺼내 둔다
        String runId = runIdOf(upload(body, agent()).andExpect(status().isCreated()));
        // 확인: 가짜 발행기에 넘어간 이벤트를 꺼낸다
        DiagnosticsRecordedEvent event = publishedEvent();
        // 이벤트의 runId가 응답의 runId와 같은지 확인한다
        assertThat(event.runId()).isEqualTo(runId);
        // 이벤트에 렌탈 ID가 실렸는지 확인한다
        assertThat(event.rentalId()).isEqualTo(RENTAL);
        // 이벤트에 출고 시점 ID가 실렸는지 확인한다
        assertThat(event.checkpointId()).isEqualTo(OUTBOUND);
        // 첫 진단이라 비교 기준(baseRunId)이 비어 있는지 확인한다
        assertThat(event.baseRunId()).isNull();
        // 플래그가 없는지 확인한다
        assertThat(event.flags()).isEmpty();
        // 이벤트 형식 버전이 1.1인지 확인한다(받는 쪽과 약속한 버전)
        assertThat(event.schemaVersion()).isEqualTo("1.1");
        // 배터리 최대 용량이 89.02%로 계산돼 실렸는지 확인한다
        assertThat(event.maxCapacityPct()).isEqualTo(89.02); // 3901 ÷ 4382 × 100, 리포트 배터리 칸(1.1)
        // 이벤트를 JSON으로 바꿔 계약 스키마로 검사하고 오류가 없는지(빈 목록) 확인한다
        assertThat(EventSchemas.validateRecorded(objectMapper.valueToTree(event))).isEmpty();
    }

    // [상황] 로그인 없이 공개 진단 요약을 조회하고, 잘못된 ID로도 조회한다.
    // [기대] 배터리·벤치마크만 보이고 부품·잠금 상태·시리얼 같은 민감한 값은 빠진다. 잘못된 ID는 400.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("공개 진단 요약은 로그인 없이 배터리·벤치마크를 주고, 부품·잠금·시리얼은 싣지 않으며 잘못된 ID는 400")
    void publicCatalogHidesComponents() throws Exception {
        // 준비: 맥 진단을 올려 저장될 진단을 만든다
        upload(Examples.bytes(Examples.MACOS), agent()).andExpect(status().isCreated());
        // 저장소에 넘어간 진단을 붙잡을 준비를 한다
        ArgumentCaptor<DiagnosticRun> saved = ArgumentCaptor.forClass(DiagnosticRun.class);
        // 저장된 진단을 붙잡는다(조회 때 돌려줄 값으로 쓰려고)
        verify(runs).saveAndFlush(saved.capture());
        // (도구) when(...).thenReturn(값) = 그 메서드가 불리면 이 값을 돌려주라고 가짜에게 정해 둔다
        // 시점으로 지금 진단을 찾으면 방금 붙잡은 진단을 돌려주게 한다
        when(runs.findByCheckpointIdInAndSupersededAtIsNull(any())).thenReturn(List.of(saved.getValue()));

        // 실행: 로그인 없이 공개 진단 요약을 조회한다(같은 시점 ID를 두 번 넣어 중복도 섞음)
        mvc.perform(get("/api/v1/diagnostics/catalog?checkpointIds=" + OUTBOUND + "," + OUTBOUND))
                // 확인: 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // 첫 항목이 출고 시점인지 확인한다
                .andExpect(jsonPath("$.items[0].checkpointId").value(OUTBOUND))
                // 배터리 최대 용량 89.02%가 보이는지 확인한다
                .andExpect(jsonPath("$.items[0].battery.maxCapacityPct").value(89.02))
                // 벤치마크가 목록으로 실렸는지 확인한다
                .andExpect(jsonPath("$.items[0].benchmarks").isArray())
                // 부품 정보는 칸 자체가 없는지 확인한다(민감한 값 숨김)
                .andExpect(jsonPath("$.items[0].components").doesNotExist())
                // 잠금·암호화 상태도 칸이 없는지 확인한다
                .andExpect(jsonPath("$.items[0].checks").doesNotExist())
                // 내부 진단 ID도 내보내지 않는지 확인한다
                .andExpect(jsonPath("$.items[0].runId").doesNotExist());
        // 형식이 틀린 ID로 조회하면 400(요청 형식 오류)인지 확인한다
        mvc.perform(get("/api/v1/diagnostics/catalog?checkpointIds=bad")).andExpect(status().isBadRequest());
    }

    // [상황] 활성화 잠금이 켜진 노트북을 출고 때 처음 진단한다.
    // [기대] 앞 진단이 없어도 ACTIVATION_LOCK_ON 플래그가 붙고, 그 기준은 이번 진단 자신이다.
    @Test
    @DisplayName("활성화 잠금이 켜진 첫 진단(출고)도 ACTIVATION_LOCK_ON 플래그가 붙고 baseRunId는 이번 진단 자신이다")
    void lockedOutboundGetsStateFlag() throws Exception {
        // 준비: 활성화 잠금이 켜진 맥 진단 예시를 읽는다
        byte[] body = Examples.bytes("valid/macos-checks.json"); // activationLock ON, diskEncryption OFF, osDiskHealth OK
        // 실행: 올리고 201인지 확인한 뒤 runId를 꺼낸다
        String runId = runIdOf(upload(body, agent()).andExpect(status().isCreated())
                // 확인: 응답의 플래그가 하나인지 확인한다
                .andExpect(jsonPath("$.flags.length()").value(1))
                // 그 플래그가 "활성화 잠금 켜짐"인지 확인한다
                .andExpect(jsonPath("$.flags[0].kind").value("ACTIVATION_LOCK_ON")));
        // 나간 이벤트를 꺼낸다
        DiagnosticsRecordedEvent event = publishedEvent();
        // (도구) extracting(...) = 목록의 각 항목에서 한 칸만 뽑아낸다. containsExactly(...)는 뽑은 값이 정확히 이것뿐인지 본다
        // 이벤트에도 같은 플래그 하나만 실렸는지 확인한다
        assertThat(event.flags()).extracting(DiagnosticsRecordedEvent.Flag::kind).containsExactly("ACTIVATION_LOCK_ON");
        // 이벤트가 계약 스키마에 맞는지 확인한다
        assertThat(EventSchemas.validateRecorded(objectMapper.valueToTree(event))).isEmpty();
        // 저장소에 넘어간 진단을 붙잡을 준비를 한다
        ArgumentCaptor<DiagnosticRun> saved = ArgumentCaptor.forClass(DiagnosticRun.class);
        // 저장된 진단을 붙잡는다
        verify(runs).saveAndFlush(saved.capture());
        // 잠금 상태가 ON으로 저장됐는지 확인한다
        assertThat(saved.getValue().checks().activationLock()).isEqualTo("ON");
        // 저장된 공개 ID가 응답 runId와 같은지 확인한다(플래그 기준이 이번 진단 자신임을 보려고)
        assertThat(saved.getValue().publicId()).isEqualTo(runId);
    }

    // [상황] 같은 렌탈에 출고 진단이 있는 상태에서 반납 진단을 올린다.
    // [기대] 이벤트의 비교 기준(baseRunId)이 바로 앞 출고 진단의 ID다.
    @Test
    @DisplayName("같은 렌탈에 앞선 진단이 있으면 이벤트 baseRunId는 바로 앞 실행의 runId")
    void baseRunIdIsPreviousRun() throws Exception {
        // 준비: 같은 렌탈의 출고 진단을 하나 만들어 둔다
        DiagnosticRun outbound = stored(OUTBOUND, RENTAL, Examples.MACOS, "outbound-key-01");
        // 그 렌탈의 가장 최근 진단을 찾으면 출고 진단을 돌려주게 한다
        when(runs.findFirstByRentalIdAndSupersededAtIsNullOrderByIdDesc(RENTAL)).thenReturn(Optional.of(outbound));
        // 실행: 반납 시점 토큰으로 진단을 올린다
        upload(Examples.bytes(Examples.MACOS), agent(RETURN, RENTAL)).andExpect(status().isCreated());
        // 확인: 나간 이벤트를 꺼낸다
        DiagnosticsRecordedEvent event = publishedEvent();
        // 비교 기준이 바로 앞 출고 진단의 ID인지 확인한다
        assertThat(event.baseRunId()).isEqualTo(outbound.publicId());
        // 이벤트가 계약 스키마에 맞는지 확인한다
        assertThat(EventSchemas.validateRecorded(objectMapper.valueToTree(event))).isEmpty();
    }

    // [상황] 약속한 형식에 맞지 않는 진단 결과를 올린다.
    // [기대] 422 DIAGNOSTIC_SCHEMA_INVALID, 저장도 이벤트도 없다.
    //        ※ 응답 코드: 422 = 형식은 맞지만 내용이 규칙에 어긋남
    @Test
    @DisplayName("스키마에 맞지 않는 결과는 422 DIAGNOSTIC_SCHEMA_INVALID로 거부되고 저장·이벤트가 없다")
    void schemaInvalidIsRejected() throws Exception {
        // 준비: 형식이 틀린 예시 4개(AC 전원 없이 벤치마크, 단위 없는 용량, 컴퓨터 이름, 사유 없는 건너뜀)를 하나씩 돈다
        for (String name : List.of("invalid/benchmark-without-ac-power.json", "invalid/capacity-without-unit.json",
                "invalid/computer-name.json", "invalid/skipped-without-reason.json")) {
            // 실행: 틀린 예시를 올린다
            upload(Examples.bytes(name), agent())
                    // 확인: 422(내용이 규칙에 어긋남)인지 확인한다
                    .andExpect(status().isUnprocessableEntity())
                    // (도구) content().contentTypeCompatibleWith(...) = 응답 본문의 형식(Content-Type)이 맞는지 본다
                    // 오류 응답이 표준 오류 형식(problem+json)인지 확인한다
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    // 오류 코드가 "진단 형식 오류"인지 확인한다
                    .andExpect(jsonPath("$.code").value("DIAGNOSTIC_SCHEMA_INVALID"))
                    // 어느 칸이 틀렸는지 알려 주는지 확인한다
                    .andExpect(jsonPath("$.errors[0].field").isNotEmpty());
        }
        // (도구) never() = 한 번도 불리지 않았어야 한다는 뜻
        // 하나도 저장되지 않았는지 확인한다
        verify(runs, never()).saveAndFlush(any());
        // 이벤트도 나가지 않았는지 확인한다
        verify(events, never()).publishRecorded(any());
    }

    // [상황] 개인정보인 컴퓨터 이름(computerName)이 들어간 진단을 올린다.
    // [기대] 오류에 device.computerName 위치가 나온다.
    @Test
    @DisplayName("모르는 키 computerName은 필드 경로 device.computerName으로 알려 준다")
    void unknownKeyPath() throws Exception {
        // 실행: 컴퓨터 이름이 들어간 진단을 올린다
        upload(Examples.bytes("invalid/computer-name.json"), agent())
                // 확인: 오류 위치가 device.computerName으로 나오는지 확인한다(어느 칸을 빼야 하는지 알려 주려고)
                .andExpect(jsonPath("$.errors[0].field").value("device.computerName"));
    }

    // [상황] 형식은 맞지만 벤치마크 중앙값이 측정값들의 가운데 값이 아니다.
    // [기대] 422 DIAGNOSTIC_SCHEMA_INVALID(서버 업무 규칙 위반).
    //        ※ 응답 코드: 422 = 형식은 맞지만 내용이 규칙에 어긋남
    @Test
    @DisplayName("스키마는 통과해도 median이 가운데 값이 아니면 422 DIAGNOSTIC_SCHEMA_INVALID")
    void serverRuleViolationIsRejected() throws Exception {
        // 준비: 맥 예시의 첫 벤치마크 중앙값(median)을 1.5로 바꿔 가운데 값이 아니게 만든다
        byte[] body = Examples.modified(Examples.MACOS,
                n -> ((com.fasterxml.jackson.databind.node.ObjectNode) n.get("benchmarks").get(0)).put("median", 1.5));
        // 실행: 고친 진단을 올린다
        upload(body, agent())
                // 확인: 422(내용이 규칙에 어긋남)인지 확인한다
                .andExpect(status().isUnprocessableEntity())
                // 오류 코드가 "진단 형식 오류"인지 확인한다
                .andExpect(jsonPath("$.code").value("DIAGNOSTIC_SCHEMA_INVALID"))
                // 오류 위치가 첫 벤치마크의 median인지 확인한다
                .andExpect(jsonPath("$.errors[0].field").value("benchmarks[0].median"));
    }

    // [상황] 본문이 JSON이 아니다.
    // [기대] 400 VALIDATION_FAILED.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("본문이 JSON이 아니면 400 VALIDATION_FAILED")
    void notJsonIsBadRequest() throws Exception {
        // 실행: JSON이 아닌 글자를 본문으로 올린다
        upload("{not json".getBytes(StandardCharsets.UTF_8), agent())
                // 확인: 400(요청 형식 오류)인지 확인한다
                .andExpect(status().isBadRequest())
                // 오류 코드가 "입력 검증 실패"인지 확인한다
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // [상황] SSD 검사를 권한이 없어 건너뛴 진단을 올린다.
    // [기대] 201. SSD 값은 비어 있고(null) 건너뛴 사유가 그대로 저장된다("못 읽은 값은 None + 사유" 원칙).
    //        ※ 응답 코드: 201 = 새로 만들어짐
    @Test
    @DisplayName("SSD를 권한이 없어 건너뛰면 201이고 값은 null, 사유는 그대로 저장된다")
    void skippedStorageIsStoredWithReason() throws Exception {
        // 실행: SSD 검사를 권한 문제로 건너뛴 윈도우 진단을 올리고 201인지 확인한다
        upload(Examples.bytes(Examples.SSD_SKIPPED), pcAgent()).andExpect(status().isCreated());
        // 확인: 저장소에 넘어간 진단을 붙잡을 준비를 한다
        ArgumentCaptor<DiagnosticRun> saved = ArgumentCaptor.forClass(DiagnosticRun.class);
        // 저장된 진단을 붙잡는다
        verify(runs).saveAndFlush(saved.capture());
        // SSD 상태가 "건너뜀"으로 저장됐는지 확인한다
        assertThat(saved.getValue().ssd().status()).isEqualTo("SKIPPED");
        // 건너뛴 사유가 "권한 없음"으로 남았는지 확인한다
        assertThat(saved.getValue().ssd().skippedReason()).isEqualTo("NO_PERMISSION");
        // 읽지 못한 사용량은 지어내지 않고 비어 있는지(null) 확인한다
        assertThat(saved.getValue().ssd().percentUsed()).isNull();
    }

    // [상황] 충전기를 꽂지 않아 벤치마크를 건너뛴 진단을 올린다.
    // [기대] 사유 NO_AC_POWER가 저장된다(배터리만으로 재면 성능이 낮게 나오므로 재지 않음).
    @Test
    @DisplayName("전원 없이 벤치마크를 건너뛰면 사유 NO_AC_POWER가 저장된다")
    void benchmarkSkipReasonIsStored() throws Exception {
        // 준비: 맥 예시를 고쳐 전원 없이 벤치마크를 건너뛴 진단을 만든다
        byte[] body = Examples.modified(Examples.MACOS, n -> {
            // 측정 조건의 AC 전원 연결을 false로 바꾼다
            ((com.fasterxml.jackson.databind.node.ObjectNode) n.get("conditions")).put("acPower", false);
            // 벤치마크 목록을 빈 목록으로 바꾼다
            n.putArray("benchmarks");
            // 건너뛴 사유를 "전원 없음"으로 적는다
            n.put("benchmarkSkippedReason", "NO_AC_POWER");
        });
        // 실행: 고친 진단을 올리고 201인지 확인한다
        upload(body, agent()).andExpect(status().isCreated());
        // 확인: 저장소에 넘어간 진단을 붙잡을 준비를 한다
        ArgumentCaptor<DiagnosticRun> saved = ArgumentCaptor.forClass(DiagnosticRun.class);
        // 저장된 진단을 붙잡는다
        verify(runs).saveAndFlush(saved.capture());
        // 건너뛴 사유가 그대로 저장됐는지 확인한다
        assertThat(saved.getValue().benchmarkSkippedReason()).isEqualTo("NO_AC_POWER");
        // 벤치마크는 하나도 저장되지 않았는지 확인한다
        assertThat(saved.getValue().benchmarks()).isEmpty();
    }

    // [상황] 진단한 기기의 시리얼이 AGENT 토큰에 적힌 렌탈 기기 시리얼과 다르다(다른 노트북을 진단).
    // [기대] 422 DEVICE_SERIAL_MISMATCH, 저장·이벤트 없음.
    //        ※ 응답 코드: 422 = 형식은 맞지만 내용이 규칙에 어긋남
    @Test
    @DisplayName("진단한 기기 시리얼이 AGENT 토큰의 등록 시리얼과 다르면 422 DEVICE_SERIAL_MISMATCH, 저장·이벤트 없음")
    void serialMismatchIsRejected() throws Exception {
        // 실행: 맥 시리얼이 담긴 토큰으로 윈도우 노트북 진단을 올린다(빌린 기기가 아닌 상황)
        upload(Examples.bytes(Examples.WINDOWS), agent(OUTBOUND, RENTAL, MAC_SERIAL))
                // 확인: 422(내용이 규칙에 어긋남)인지 확인한다
                .andExpect(status().isUnprocessableEntity())
                // 오류 코드가 "시리얼 불일치"인지 확인한다
                .andExpect(jsonPath("$.code").value("DEVICE_SERIAL_MISMATCH"));
        // 저장되지 않았는지 확인한다
        verify(runs, never()).saveAndFlush(any());
        // 이벤트도 나가지 않았는지 확인한다
        verify(events, never()).publishRecorded(any());
    }

    // [상황] 앞선 진단이 없는 첫 진단에서 시리얼이 다른 경우와 같은 경우.
    // [기대] 다르면 거부, 같으면 저장된다. 첫 진단도 등록된 기기인지 확인한다.
    @Test
    @DisplayName("첫 진단이라도 등록 시리얼과 다르면 거부하고, 같으면 앞선 진단 없이도 저장한다")
    void serialIsCheckedAgainstClaimEvenOnFirstRun() throws Exception {
        // 실행: 윈도우 시리얼 토큰으로 맥 진단을 첫 진단으로 올린다
        upload(Examples.bytes(Examples.MACOS), agent(OUTBOUND, RENTAL, PC_SERIAL))
                // 확인: 앞선 진단이 없어도 422로 거부되는지 확인한다
                .andExpect(status().isUnprocessableEntity())
                // 오류 코드가 "시리얼 불일치"인지 확인한다
                .andExpect(jsonPath("$.code").value("DEVICE_SERIAL_MISMATCH"));
        // 시리얼이 맞는 윈도우 진단은 201로 저장되는지 확인한다
        upload(Examples.bytes(Examples.WINDOWS), pcAgent()).andExpect(status().isCreated());
    }

    // [상황] 기기 시리얼이 들어 있지 않은 예전 방식의 AGENT 토큰으로 올린다.
    // [기대] 403 FORBIDDEN(어느 기기인지 확인할 수 없으므로 받지 않음).
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("deviceSerial 클레임이 없는 AGENT 토큰(예전 페어링 코드)은 403 FORBIDDEN")
    void agentWithoutSerialIsForbidden() throws Exception {
        // 실행: 시리얼 정보가 없는 옛 에이전트 토큰으로 올린다
        upload(Examples.bytes(Examples.MACOS), agent(OUTBOUND, RENTAL, null))
                // 확인: 403(권한 없음)인지 확인한다
                .andExpect(status().isForbidden())
                // 오류 코드가 FORBIDDEN인지 확인한다
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 저장되지 않았는지 확인한다
        verify(runs, never()).saveAndFlush(any());
    }

    // ---- 이야기 2: 다시 보내기와 중복

    // [상황] 네트워크가 끊겨 에이전트가 같은 요청 키·같은 본문으로 다시 보낸다.
    // [기대] 처음과 같은 응답(201, 같은 runId)을 돌려주고 새 진단·이벤트를 만들지 않는다.
    //        ※ 응답 코드: 201 = 새로 만들어짐
    @Test
    @DisplayName("같은 키·같은 본문으로 다시 보내면 처음 응답(201, 같은 runId)이고 새 행·이벤트가 없다")
    void sameKeySameBodyReplays() throws Exception {
        // 준비: 같은 키·같은 본문으로 이미 저장된 진단을 만든다
        DiagnosticRun existing = stored(OUTBOUND, RENTAL, Examples.MACOS, KEY);
        // 출고 시점의 지금 진단을 찾으면 그 진단을 돌려주게 한다
        when(runs.findByCheckpointIdAndSupersededAtIsNull(OUTBOUND)).thenReturn(Optional.of(existing));
        // 실행: 같은 키·같은 본문으로 다시 올린다(네트워크 재시도 흉내)
        upload(Examples.bytes(Examples.MACOS), agent())
                // 확인: 처음과 같은 201인지 확인한다
                .andExpect(status().isCreated())
                // runId가 이미 저장된 진단의 것인지 확인한다
                .andExpect(jsonPath("$.runId").value(existing.publicId()))
                // 플래그가 비어 있는지 확인한다
                .andExpect(jsonPath("$.flags").isEmpty());
        // 새로 저장하지 않았는지 확인한다
        verify(runs, never()).saveAndFlush(any());
        // 이벤트도 다시 나가지 않았는지 확인한다
        verify(events, never()).publishRecorded(any());
    }

    // [상황] 같은 요청 키로 내용이 다른 진단을 보낸다.
    // [기대] 422 IDEMPOTENCY_KEY_CONFLICT(키 재사용 오류).
    //        ※ 응답 코드: 422 = 형식은 맞지만 내용이 규칙에 어긋남
    @Test
    @DisplayName("같은 키에 다른 본문이면 422 IDEMPOTENCY_KEY_CONFLICT")
    void sameKeyOtherBodyConflicts() throws Exception {
        // 준비: 같은 키로 맥 진단이 이미 저장돼 있게 한다
        when(runs.findByCheckpointIdAndSupersededAtIsNull(OUTBOUND)).thenReturn(Optional.of(stored(OUTBOUND, RENTAL, Examples.MACOS, KEY)));
        // 실행: 같은 키로 다른 본문(윈도우 진단)을 올린다
        upload(Examples.bytes(Examples.SSD_SKIPPED), agent())
                // 확인: 422인지 확인한다
                .andExpect(status().isUnprocessableEntity())
                // 오류 코드가 "요청 키 충돌"인지 확인한다
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    // [상황] 이미 진단이 있는 시점에 다른 키로 또 올린다.
    // [기대] 409 DIAGNOSTIC_ALREADY_UPLOADED. 한 시점에는 진단 하나만(바꾸려면 관리자의 "진단 다시 하기").
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("이미 진단이 있는 시점에 다른 키로 올리면 409 DIAGNOSTIC_ALREADY_UPLOADED")
    void secondUploadOnCheckpointConflicts() throws Exception {
        // 준비: 다른 키로 올린 진단이 이미 그 시점에 있게 한다
        when(runs.findByCheckpointIdAndSupersededAtIsNull(OUTBOUND))
                .thenReturn(Optional.of(stored(OUTBOUND, RENTAL, Examples.MACOS, "first-key-0001")));
        // 실행: 이번 키로 같은 시점에 올린다
        upload(Examples.bytes(Examples.MACOS), agent())
                // 확인: 409(이미 있음)인지 확인한다
                .andExpect(status().isConflict())
                // 오류 코드가 "이미 올림"인지 확인한다
                .andExpect(jsonPath("$.code").value("DIAGNOSTIC_ALREADY_UPLOADED"));
    }

    // [상황] 요청 키(Idempotency-Key)가 없거나 8자보다 짧다.
    // [기대] 400 VALIDATION_FAILED.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("Idempotency-Key가 없거나 8자 미만이면 400 VALIDATION_FAILED")
    void idempotencyKeyRequired() throws Exception {
        // 실행: 요청 키 헤더 없이 진단을 올린다
        mvc.perform(post(RUNS).with(agent()).contentType(MediaType.APPLICATION_JSON)
                .content(Examples.bytes(Examples.MACOS)))
                // 확인: 400(요청 형식 오류)인지 확인한다
                .andExpect(status().isBadRequest())
                // 오류 코드가 "입력 검증 실패"인지 확인한다
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 8자보다 짧은 요청 키("short")로 다시 올린다
        mvc.perform(post(RUNS).with(agent()).header("Idempotency-Key", "short").contentType(MediaType.APPLICATION_JSON)
                .content(Examples.bytes(Examples.MACOS)))
                // 이것도 400인지 확인한다
                .andExpect(status().isBadRequest())
                // 오류 코드가 "입력 검증 실패"인지 확인한다
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- 이야기 3: 리포트용 시점 진단 조회

    // [상황] 출고 시점의 진단 요약을 조회한다.
    // [기대] 배터리·SSD·부품·벤치마크 요약이 오고, 앞 시점(previous)은 없으며 플래그는 빈 목록.
    @Test
    @DisplayName("출고 시점 조회: 배터리·SSD·부품·벤치마크 요약, previous는 null, flags는 빈 배열")
    void checkpointSummary() throws Exception {
        // 준비: 맥 출고 진단을 하나 만들어 둔다
        DiagnosticRun run = stored(OUTBOUND, RENTAL, Examples.MACOS, KEY);
        // 출고 시점의 지금 진단을 찾으면 그 진단을 돌려주게 한다
        when(runs.findByCheckpointIdAndSupersededAtIsNull(OUTBOUND)).thenReturn(Optional.of(run));
        // 실행: 관리자 토큰으로 출고 시점 진단 요약을 조회한다
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin()))
                // 확인: 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // 조회한 시점 ID가 그대로 오는지 확인한다
                .andExpect(jsonPath("$.checkpointId").value(OUTBOUND))
                // 지금 진단이 만들어 둔 진단인지 확인한다
                .andExpect(jsonPath("$.current.runId").value(run.publicId()))
                // 운영체제가 MACOS인지 확인한다
                .andExpect(jsonPath("$.current.os").value("MACOS"))
                // 배터리 충전 횟수 214가 보이는지 확인한다
                .andExpect(jsonPath("$.current.battery.cycleCount").value(214))
                // 설계 용량 4382가 보이는지 확인한다
                .andExpect(jsonPath("$.current.battery.designCapacity").value(4382))
                // 완충 용량 3901이 보이는지 확인한다
                .andExpect(jsonPath("$.current.battery.fullChargeCapacity").value(3901))
                // 용량 단위가 mAh인지 확인한다
                .andExpect(jsonPath("$.current.battery.capacityUnit").value("mAh"))
                // 최대 용량 비율이 89.02%로 계산됐는지 확인한다
                .andExpect(jsonPath("$.current.battery.maxCapacityPct").value(89.02))
                // 배터리 상태가 NORMAL인지 확인한다
                .andExpect(jsonPath("$.current.battery.condition").value("NORMAL"))
                // SSD 사용량 3%가 보이는지 확인한다
                .andExpect(jsonPath("$.current.ssd.percentUsed").value(3))
                // SSD 예비 공간 100%가 보이는지 확인한다
                .andExpect(jsonPath("$.current.ssd.availableSparePct").value(100))
                // SSD를 읽었으니 건너뜀 사유 칸이 없는지 확인한다
                .andExpect(jsonPath("$.current.ssd.skippedReason").doesNotExist())
                // 첫 부품이 CPU인지 확인한다
                .andExpect(jsonPath("$.current.components[0].kind").value("CPU"))
                // 첫 벤치마크가 CPU 단일 코어 점수인지 확인한다
                .andExpect(jsonPath("$.current.benchmarks[0].metric").value("CPU_SINGLE"))
                // 출고는 첫 시점이라 앞 시점(previous)이 없는지 확인한다
                .andExpect(jsonPath("$.previous").doesNotExist())
                // 플래그가 비어 있는지 확인한다
                .andExpect(jsonPath("$.flags").isEmpty());
    }

    // [상황] 반납 시점을 조회한다.
    // [기대] previous에 같은 렌탈의 바로 앞 시점(출고) 요약이 함께 와서, 리포트가 한 번의 조회로 두 시점을 비교할 수 있다.
    @Test
    @DisplayName("반납 시점 조회의 previous는 같은 렌탈 직전 시점 요약이다(두 번 측정한 값을 한 번의 조회로)")
    void checkpointWithPrevious() throws Exception {
        // 준비: SSD를 건너뛴 출고 진단을 만든다
        DiagnosticRun outbound = stored(OUTBOUND, RENTAL, Examples.SSD_SKIPPED, "outbound-key-01");
        // 같은 렌탈의 반납 진단을 만든다
        DiagnosticRun returned = stored(RETURN, RENTAL, Examples.WINDOWS, KEY);
        // 반납 시점의 지금 진단을 찾으면 반납 진단을 돌려주게 한다
        when(runs.findByCheckpointIdAndSupersededAtIsNull(RETURN)).thenReturn(Optional.of(returned));
        // (도구) eq(값) = 정확히 그 값일 때만, anyLong() = 어떤 숫자든 상관없다는 뜻
        // 같은 렌탈에서 바로 앞 진단을 찾으면 출고 진단을 돌려주게 한다
        when(runs.findFirstByRentalIdAndIdLessThanAndSupersededAtIsNullOrderByIdDesc(eq(RENTAL), anyLong()))
                .thenReturn(Optional.of(outbound));
        // 실행: 고객 토큰으로 반납 시점을 조회한다
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + RETURN).with(customer(RENTAL)))
                // 확인: 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // 지금 진단이 반납 진단인지 확인한다
                .andExpect(jsonPath("$.current.runId").value(returned.publicId()))
                // 반납 진단(윈도우)의 용량 단위가 mWh인지 확인한다
                .andExpect(jsonPath("$.current.battery.capacityUnit").value("mWh"))
                // 앞 시점이 출고 진단인지 확인한다(한 번 조회로 두 시점을 받는지)
                .andExpect(jsonPath("$.previous.runId").value(outbound.publicId()))
                // 출고 때 읽지 못한 SSD 사용량은 칸이 없는지 확인한다
                .andExpect(jsonPath("$.previous.ssd.percentUsed").doesNotExist())
                // 대신 건너뛴 사유 "권한 없음"이 보이는지 확인한다
                .andExpect(jsonPath("$.previous.ssd.skippedReason").value("NO_PERMISSION"));
    }

    // [상황] 배터리가 없는 기기(데스크톱 등, NO_BATTERY)의 진단을 조회한다.
    // [기대] battery 값이 null이다.
    @Test
    @DisplayName("배터리가 없는 기기(NO_BATTERY)는 battery가 null")
    void noBatteryIsNull() throws Exception {
        // 준비: 윈도우 예시를 고쳐 배터리가 없는 기기의 진단을 만든다
        DiagnosticRun run = stored(OUTBOUND, RENTAL, Examples.modified(Examples.WINDOWS, n -> {
            // JSON에서 battery 칸을 꺼낸다(안의 값을 고치기 위해)
            var b = (com.fasterxml.jackson.databind.node.ObjectNode) n.get("battery");
            // 배터리 상태를 "배터리 없음"으로 바꾼다
            b.put("status", "NO_BATTERY");
            // 건너뜀 사유는 비운다(건너뛴 게 아니라 원래 없는 것이라서)
            b.putNull("skippedReason");
            // 충전 횟수를 비운다
            b.putNull("cycleCount");
            // 설계 용량을 비운다
            b.putNull("designCapacity");
            // 완충 용량을 비운다
            b.putNull("fullChargeCapacity");
            // OS가 보여 준 최대 용량도 비운다
            b.putNull("osMaxCapacityPercent");
            // OS가 보여 준 배터리 상태도 비운다
            b.putNull("osCondition");
        }), KEY);
        // 출고 시점의 지금 진단을 찾으면 이 진단을 돌려주게 한다
        when(runs.findByCheckpointIdAndSupersededAtIsNull(OUTBOUND)).thenReturn(Optional.of(run));
        // 실행: 관리자로 출고 시점을 조회한다
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin()))
                // 확인: 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // 배터리 칸 자체가 없는지 확인한다(없는 배터리를 0으로 보이지 않게)
                .andExpect(jsonPath("$.current.battery").doesNotExist());
    }

    // [상황] 배터리를 읽지 못한 진단을 조회한다.
    // [기대] battery 값은 null이고 skippedReason에 못 읽은 사유 코드가 나온다.
    @Test
    @DisplayName("배터리를 읽지 못했으면 battery 값은 null이고 skippedReason에 사유 코드가 나온다")
    void skippedBatteryShowsReason() throws Exception {
        // 준비: 윈도우 예시를 고쳐 배터리를 권한 문제로 읽지 못한 진단을 만든다
        DiagnosticRun run = stored(OUTBOUND, RENTAL, Examples.modified(Examples.WINDOWS, n -> {
            // JSON에서 battery 칸을 꺼낸다
            var b = (com.fasterxml.jackson.databind.node.ObjectNode) n.get("battery");
            // 배터리 상태를 "건너뜀"으로 바꾼다
            b.put("status", "SKIPPED");
            // 건너뛴 사유를 "권한 없음"으로 적는다
            b.put("skippedReason", "NO_PERMISSION");
            // 충전 횟수를 비운다
            b.putNull("cycleCount");
            // 설계 용량을 비운다
            b.putNull("designCapacity");
            // 완충 용량을 비운다
            b.putNull("fullChargeCapacity");
            // OS가 보여 준 최대 용량도 비운다
            b.putNull("osMaxCapacityPercent");
            // OS가 보여 준 배터리 상태도 비운다
            b.putNull("osCondition");
        }), KEY);
        // 출고 시점의 지금 진단을 찾으면 이 진단을 돌려주게 한다
        when(runs.findByCheckpointIdAndSupersededAtIsNull(OUTBOUND)).thenReturn(Optional.of(run));
        // 실행: 관리자로 출고 시점을 조회한다
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(admin()))
                // 확인: 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // 건너뛴 사유 "권한 없음"이 보이는지 확인한다
                .andExpect(jsonPath("$.current.battery.skippedReason").value("NO_PERMISSION"))
                // 읽지 못한 충전 횟수는 칸이 없는지 확인한다
                .andExpect(jsonPath("$.current.battery.cycleCount").doesNotExist())
                // 최대 용량 비율도 계산하지 않고 칸이 없는지 확인한다
                .andExpect(jsonPath("$.current.battery.maxCapacityPct").doesNotExist());
    }

    // [상황] 다른 렌탈의 고객, QR 촬영 전용 토큰, 진단이 없는 시점으로 조회한다.
    // [기대] 다른 렌탈 고객은 403 OUT_OF_SCOPE, 촬영 토큰은 403 FORBIDDEN, 진단 없는 시점은 404.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일), 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("다른 렌탈 고객은 403 OUT_OF_SCOPE, 촬영 JWT는 403 FORBIDDEN, 진단 없는 시점은 404")
    void checkpointScope() throws Exception {
        // 준비: 출고 시점에 진단이 있게 한다
        when(runs.findByCheckpointIdAndSupersededAtIsNull(OUTBOUND)).thenReturn(Optional.of(stored(OUTBOUND, RENTAL, Examples.MACOS, KEY)));
        // 실행+확인: 다른 렌탈의 고객이 조회한다
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(customer(OTHER_RENTAL)))
                // 403(권한 없음)인지 확인한다
                .andExpect(status().isForbidden())
                // 오류 코드가 "범위 밖"인지 확인한다
                .andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        // 촬영용 토큰으로 조회한다
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(capture()))
                // 403인지 확인한다
                .andExpect(status().isForbidden())
                // 오류 코드가 FORBIDDEN인지 확인한다
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 진단이 없는 반납 시점을 관리자로 조회한다
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + RETURN).with(admin()))
                // 404(찾을 수 없음)인지 확인한다
                .andExpect(status().isNotFound())
                // 오류 코드가 NOT_FOUND인지 확인한다
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // NanoID 모양이 아닌 시점 ID로 조회한다
        mvc.perform(get("/api/v1/diagnostics/checkpoints/not-a-nanoid").with(admin()))
                // 400(요청 형식 오류)인지 확인한다
                .andExpect(status().isBadRequest());
    }

    // ---- 이야기 4: 범위 확인

    // [상황] 관리자·고객·부스 토큰으로 진단을 올리고, 에이전트 토큰으로 조회해 본다.
    // [기대] 모두 403. 진단을 올리는 건 에이전트만, 조회는 에이전트가 못 한다.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("ADMIN·CUSTOMER·BOOTH 토큰으로 업로드하면 403, AGENT로 조회하면 403")
    void rolesAreChecked() throws Exception {
        // 실행+확인: 관리자 토큰으로 올리면 403인지 확인한다(올리기는 에이전트만)
        upload(Examples.bytes(Examples.MACOS), admin()).andExpect(status().isForbidden());
        // 고객 토큰으로 올려도 403인지 확인한다
        upload(Examples.bytes(Examples.MACOS), customer(RENTAL)).andExpect(status().isForbidden());
        // 부스 토큰으로 올려도 403인지 확인한다
        upload(Examples.bytes(Examples.MACOS), booth()).andExpect(status().isForbidden());
        // 에이전트 토큰으로 조회한다(조회는 관리자·고객만)
        mvc.perform(get("/api/v1/diagnostics/checkpoints/" + OUTBOUND).with(agent()))
                // 403인지 확인한다
                .andExpect(status().isForbidden())
                // 오류 코드가 FORBIDDEN인지 확인한다
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // [상황] AGENT 토큰 안의 시점 ID나 렌탈 ID가 올바른 21자 NanoID 형식이 아니다.
    // [기대] 403 FORBIDDEN.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("AGENT 토큰의 scope나 rentalId가 NanoID가 아니면 403 FORBIDDEN")
    void agentClaimsMustBePublicIds() throws Exception {
        // 실행+확인: 시점 ID가 NanoID 모양이 아닌 에이전트 토큰으로 올린다
        upload(Examples.bytes(Examples.MACOS), agent("not-a-checkpoint", RENTAL))
                // 403인지 확인한다
                .andExpect(status().isForbidden())
                // 오류 코드가 FORBIDDEN인지 확인한다
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 렌탈 ID가 NanoID 모양이 아닌 토큰으로 올린다
        upload(Examples.bytes(Examples.MACOS), agent(OUTBOUND, "x"))
                // 이것도 403인지 확인한다
                .andExpect(status().isForbidden());
    }

    // [상황] 토큰 없이 부른다.
    // [기대] 401 UNAUTHENTICATED.
    //        ※ 응답 코드: 401 = 로그인 필요(토큰이 없거나 만료·위조됨)
    @Test
    @DisplayName("토큰이 없으면 401 UNAUTHENTICATED")
    void unauthenticated() throws Exception {
        // 실행: 토큰 없이 진단을 올린다
        mvc.perform(post(RUNS).header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content(Examples.bytes(Examples.MACOS)))
                // 확인: 401(로그인 필요)인지 확인한다
                .andExpect(status().isUnauthorized())
                // 오류 코드가 UNAUTHENTICATED인지 확인한다
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---- 진단 다시 하기(11/13)

    // 테스트 도구: 시점 ID로 "진단 다시 하기" 주소를 만든다
    private static String redoPath(String checkpointId) {
        // 시점 ID를 끼워 넣은 주소를 돌려준다(주소를 테스트마다 반복해 쓰지 않으려고)
        return "/api/v1/diagnostics/checkpoints/" + checkpointId + "/redo";
    }

    // [상황] 봉인 전 출고 시점에서 관리자가 "진단 다시 하기"를 누른다(예: 충전기 없이 잘못 잰 경우).
    // [기대] 200. 지금 진단을 지우지 않고 "이전 진단"으로 넘겨 기록을 남긴다.
    //        ※ 응답 코드: 200 = 정상 처리됨
    @Test
    @DisplayName("봉인 전 시점에서 관리자가 진단 다시 하기를 하면 200이고, 지금 진단은 지우지 않고 이전 진단으로 남는다")
    void adminRedoSupersedesCurrentRun() throws Exception {
        // 준비: 맥 출고 진단을 만든다
        DiagnosticRun run = stored(OUTBOUND, RENTAL, Examples.MACOS, KEY);
        // 출고 시점의 지금 진단을 찾으면 그 진단을 돌려주게 한다
        when(runs.findByCheckpointIdAndSupersededAtIsNull(OUTBOUND)).thenReturn(Optional.of(run));
        // 실행: 관리자로 출고 시점 "진단 다시 하기"를 부른다
        mvc.perform(post(redoPath(OUTBOUND)).with(admin()))
                // 확인: 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // 시점 ID가 그대로 오는지 확인한다
                .andExpect(jsonPath("$.checkpointId").value(OUTBOUND))
                // 밀려난 진단이 지금 진단인지 확인한다
                .andExpect(jsonPath("$.supersededRunId").value(run.publicId()))
                // 밀려난 시각이 채워졌는지 확인한다
                .andExpect(jsonPath("$.supersededAt").isNotEmpty());
        // 진단 객체에도 밀려난 시각이 적혔는지 확인한다
        assertThat(run.supersededAt()).isNotNull();
        // 그 진단을 지우지 않고 바뀐 상태로 저장했는지 확인한다
        verify(runs).saveAndFlush(run);
        // 렌탈 단위로 잠금을 걸었는지 확인한다(동시에 올리기·다시 하기가 섞이지 않게)
        verify(runs).lockRental(RENTAL);
        // 진단을 지우는 일은 한 번도 없었는지 확인한다(기록을 남기려고)
        verify(runs, never()).delete(any());
    }

    // [상황] 이미 봉인된 시점에서 "진단 다시 하기"를 누른다.
    // [기대] 409 RECORD_ALREADY_SEALED, 지금 진단은 그대로다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("봉인된 시점은 진단 다시 하기가 409 RECORD_ALREADY_SEALED이고 지금 진단은 그대로다")
    void sealedCheckpointCannotRedo() throws Exception {
        // 준비: 맥 출고 진단을 만든다
        DiagnosticRun run = stored(OUTBOUND, RENTAL, Examples.MACOS, KEY);
        // 출고 시점의 지금 진단을 찾으면 그 진단을 돌려주게 한다
        when(runs.findByCheckpointIdAndSupersededAtIsNull(OUTBOUND)).thenReturn(Optional.of(run));
        // 출고 시점이 봉인됐다고 답하게 한다
        when(seals.isSealed(OUTBOUND)).thenReturn(true);
        // 실행: 관리자로 "진단 다시 하기"를 부른다
        mvc.perform(post(redoPath(OUTBOUND)).with(admin()))
                // 확인: 409(지금 상태와 맞지 않음)인지 확인한다
                .andExpect(status().isConflict())
                // 오류 코드가 "이미 봉인됨"인지 확인한다
                .andExpect(jsonPath("$.code").value("RECORD_ALREADY_SEALED"));
        // 지금 진단이 밀려나지 않았는지 확인한다
        assertThat(run.supersededAt()).isNull();
        // 아무것도 저장하지 않았는지 확인한다
        verify(runs, never()).saveAndFlush(any());
    }

    // [상황] 진단이 아직 없는 시점에서 "진단 다시 하기"를 누른다.
    // [기대] 404.
    //        ※ 응답 코드: 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("지금 진단이 없는 시점의 진단 다시 하기는 404")
    void redoWithoutRunIsNotFound() throws Exception {
        // 실행: 진단이 없는 출고 시점에 "진단 다시 하기"를 부른다(가짜 저장소는 기본으로 빈 값을 돌려줌)
        mvc.perform(post(redoPath(OUTBOUND)).with(admin()))
                // 확인: 404(찾을 수 없음)인지 확인한다
                .andExpect(status().isNotFound())
                // 오류 코드가 NOT_FOUND인지 확인한다
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // [상황] 고객 토큰과 에이전트 토큰으로 "진단 다시 하기"를 부른다.
    // [기대] 둘 다 403. 관리자만 할 수 있다.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("진단 다시 하기는 관리자만: 고객·에이전트 토큰은 403")
    void redoIsAdminOnly() throws Exception {
        // 실행+확인: 고객 토큰으로 "진단 다시 하기"를 부른다
        mvc.perform(post(redoPath(OUTBOUND)).with(customer(RENTAL)))
                // 403(권한 없음)인지 확인한다
                .andExpect(status().isForbidden());
        // 에이전트 토큰으로 부른다
        mvc.perform(post(redoPath(OUTBOUND)).with(agent()))
                // 이것도 403인지 확인한다
                .andExpect(status().isForbidden());
        // 아무것도 저장하지 않았는지 확인한다
        verify(runs, never()).saveAndFlush(any());
    }

    // [상황] "진단 다시 하기"로 지금 진단이 비워진 시점에 새 진단을 올린다.
    // [기대] 201로 저장된다.
    //        ※ 응답 코드: 201 = 새로 만들어짐
    @Test
    @DisplayName("진단 다시 하기 뒤에는 같은 시점에 새 진단을 올릴 수 있다(지금 진단이 없으므로 201)")
    void uploadAfterRedoIsStored() throws Exception {
        // 다시 하기로 지금 진단이 비면 조회는 빈 값이다(Mockito 기본). 새 키로 올리면 저장된다
        // 실행: 지금 진단이 빈 시점에 새로 올리고 201인지 확인한다
        upload(Examples.bytes(Examples.MACOS), agent()).andExpect(status().isCreated());
        // 확인: 새 진단이 저장됐는지 확인한다
        verify(runs).saveAndFlush(any(DiagnosticRun.class));
    }

    // ---- 도우미

    // 테스트 도구: 업로드 응답 JSON에서 runId를 꺼낸다
    private String runIdOf(ResultActions result) throws Exception {
        // 응답 본문 글자를 JSON으로 읽어 runId 칸 값을 글자로 돌려준다
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("runId").asText();
    }

    // 테스트 도구: 가짜 발행기에 넘어간 "진단 기록됨" 이벤트를 꺼낸다
    private DiagnosticsRecordedEvent publishedEvent() {
        // 넘어간 이벤트를 붙잡을 도구를 만든다
        ArgumentCaptor<DiagnosticsRecordedEvent> captor = ArgumentCaptor.forClass(DiagnosticsRecordedEvent.class);
        // 발행이 한 번 불렸는지 확인하고 그때 넘어간 이벤트를 붙잡는다
        verify(events).publishRecorded(captor.capture());
        // 붙잡은 이벤트를 돌려준다
        return captor.getValue();
    }

    // 테스트 도구: 예시 파일 이름으로 "이미 저장된 진단"을 만든다
    private DiagnosticRun stored(String checkpointId, String rentalId, String example, String key) throws Exception {
        // 예시 파일을 바이트로 읽어 아래 도우미에 넘긴다
        return stored(checkpointId, rentalId, Examples.bytes(example), key);
    }

    // 테스트 도구: JSON 바이트로 "이미 저장된 진단"을 만든다(DB 없이 저장된 상태를 흉내 내려고)
    private DiagnosticRun stored(String checkpointId, String rentalId, byte[] body, String key) throws Exception {
        // JSON을 업로드 객체로 바꾼다
        DiagnosticUpload upload = objectMapper.readValue(body, DiagnosticUpload.class);
        // 원본의 SHA-256 해시를 계산한다
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        // 새 공개 ID·시점·렌탈·요청 키·원본·해시·지금 시각으로 진단을 만들고 내부 번호를 붙여 돌려준다
        return withId(DiagnosticRun.of(com.gakdo.trace.diagnostics.common.infra.PublicIds.next(), checkpointId,
                rentalId, key, upload, new DiagnosticRun.RawBody(new String(body, StandardCharsets.UTF_8), sha),
                Times.now()));
    }

    // 테스트 도구: 진단에 DB처럼 내부 번호(id)를 붙인다
    private DiagnosticRun withId(DiagnosticRun run) throws Exception {
        // (도구) 리플렉션 = 코드 밖에서 객체의 숨은(private) 칸을 찾아 읽고 쓰는 자바 기능
        // 진단 클래스의 숨은 id 칸을 찾는다(보통은 DB가 채우는 칸이라 직접 넣을 방법이 없어서)
        Field id = DiagnosticRun.class.getDeclaredField("id");
        // private 칸이라도 값을 넣을 수 있게 연다
        id.setAccessible(true);
        // 1씩 늘린 번호를 id 칸에 넣는다
        id.set(run, ids.incrementAndGet());
        // 번호가 붙은 진단을 돌려준다
        return run;
    }

}
