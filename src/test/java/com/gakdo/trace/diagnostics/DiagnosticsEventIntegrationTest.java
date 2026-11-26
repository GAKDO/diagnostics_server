// 이 파일이 확인하는 것: 진단을 올리면 메시지 큐(RabbitMQ)로 "진단이 기록됨" 이벤트가 실제로 나가는지,
// 진짜 DB와 진짜 메시지 큐를 띄워 끝까지 확인한다. Docker가 없는 곳에서는 건너뛰고 GitHub Actions에서 돈다.

package com.gakdo.trace.diagnostics;

import static com.gakdo.trace.diagnostics.support.TestJwts.OUTBOUND;
import static com.gakdo.trace.diagnostics.support.TestJwts.RENTAL;
import static com.gakdo.trace.diagnostics.support.TestJwts.agent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.diagnostics.support.EventSchemas;
import com.gakdo.trace.diagnostics.support.Examples;

/**
 * 실제 PostgreSQL·RabbitMQ로 업로드 → trace.events의 trace.diagnostics.recorded 메시지를 받아
 * 계약 스키마·message_id·응답 runId를 확인한다. Docker가 없으면 건너뛴다(CI에서 돈다).
 */
// (도구) @SpringBootTest = 서버 전체를 띄우는 통합 테스트. @AutoConfigureMockMvc = MockMvc도 쓸 수 있게 한다. @Testcontainers(disabledWithoutDocker = true) = Docker가 없으면 건너뛴다
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DiagnosticsEventIntegrationTest {

    // (도구) @Container = 테스트 동안 Docker로 띄울 컨테이너. @ServiceConnection = 띄운 주소를 스프링 설정에 자동으로 넣어 준다
    // PostgreSQL 16 컨테이너(trace DB)
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("trace");

    // RabbitMQ 3.13 컨테이너(메시지 큐)
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    // (도구) @Autowired = 스프링이 만들어 둔 부품을 넣어 준다. MockMvc = 진짜 네트워크 없이 HTTP 요청을 흉내 내는 도구
    @Autowired
    private MockMvc mvc;
    // (도구) RabbitTemplate = 메시지 큐에서 메시지를 보내고 받는 도구
    @Autowired
    private RabbitTemplate rabbit;
    // (도구) RabbitAdmin = 큐를 만들고 연결(바인딩)하는 관리 도구
    @Autowired
    private RabbitAdmin admin;
    // JSON 읽기 도구
    @Autowired
    private ObjectMapper objectMapper;

    // [상황] 에이전트 토큰으로 맥 진단 결과를 올리고, 테스트용 큐에서 나간 이벤트를 받는다.
    // [기대] 업로드는 201. 이벤트가 약속한 형식에 맞고, 메시지 ID는 이벤트 ID와 같으며,
    //        응답의 runId·렌탈·출고 시점이 이벤트와 일치한다. 첫 진단이라 비교 기준(baseRunId)은 비어 있다.
    //        ※ 응답 코드: 201 = 새로 만들어짐
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("업로드하면 trace.events에 trace.diagnostics.recorded가 계약 형식으로 나가고 message_id는 eventId다")
    void recordedEventIsPublished() throws Exception {
        // 준비: 시험이 끝나면 저절로 지워지는 테스트용 큐를 정의한다
        Queue queue = new Queue("test.diagnostics.recorded", false, false, true);
        // 준비: 그 큐를 RabbitMQ에 만든다
        admin.declareQueue(queue);
        // 준비: trace.events 교환소의 "진단이 기록됨" 메시지가 그 큐로 오도록 연결한다
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("trace.events"))
                .with("trace.diagnostics.recorded"));

        // (도구) perform(post(주소).with(토큰)...) = 그 토큰으로 POST 요청을 보낸다. andExpect = 응답 검사, andReturn()... = 응답 본문 꺼내기
        // 실행: AGENT 토큰·Idempotency-Key로 맥 진단 예시를 올리고 응답 본문을 받는다
        String response = mvc.perform(post("/api/v1/diagnostics/runs").with(agent())
                .header("Idempotency-Key", "event-key-0001")
                .contentType(MediaType.APPLICATION_JSON).content(Examples.bytes(Examples.MACOS)))
                // (도구) status().isCreated() = 응답 코드가 201(새로 만들어짐)인지 본다
                // 확인: 201이어야 한다
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // 실행: 테스트 큐에서 메시지를 최대 10초 기다려 받는다
        Message message = rabbit.receive(queue.getName(), 10_000);
        // (도구) assertThat(값).isNotNull() = 값이 있어야 통과하는 AssertJ 검사
        // 확인: 메시지가 와야 한다
        assertThat(message).isNotNull();
        // 메시지 본문을 JSON으로 읽는다
        JsonNode event = objectMapper.readTree(message.getBody());
        // (도구) isEmpty() = 비어 있어야 통과
        // 확인: 계약 이벤트 스키마 검사에서 틀린 점이 없어야 한다
        assertThat(EventSchemas.validateRecorded(event)).isEmpty();
        // (도구) isEqualTo(값) = 그 값과 같아야 통과
        // 확인: 메시지 ID가 이벤트 ID와 같아야 한다(받는 쪽이 중복을 거를 수 있게)
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.get("eventId").asText());
        // 확인: 이벤트의 runId가 업로드 응답의 runId와 같아야 한다
        assertThat(event.get("runId").asText()).isEqualTo(objectMapper.readTree(response).get("runId").asText());
        // 확인: 렌탈 ID가 토큰의 렌탈이어야 한다
        assertThat(event.get("rentalId").asText()).isEqualTo(RENTAL);
        // 확인: 시점 ID가 출고 시점이어야 한다
        assertThat(event.get("checkpointId").asText()).isEqualTo(OUTBOUND);
        // (도구) isTrue() = 값이 true여야 통과
        // 확인: 첫 진단이라 비교 기준(baseRunId)이 비어 있어야 한다
        assertThat(event.get("baseRunId").isNull()).isTrue();
    }
}
