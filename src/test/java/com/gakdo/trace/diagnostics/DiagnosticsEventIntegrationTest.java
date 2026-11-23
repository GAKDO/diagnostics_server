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
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DiagnosticsEventIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("trace");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private RabbitAdmin admin;
    @Autowired
    private ObjectMapper objectMapper;

    // [상황] 에이전트 토큰으로 맥 진단 결과를 올리고, 테스트용 큐에서 나간 이벤트를 받는다.
    // [기대] 업로드는 201. 이벤트가 약속한 형식에 맞고, 메시지 ID는 이벤트 ID와 같으며,
    //        응답의 runId·렌탈·출고 시점이 이벤트와 일치한다. 첫 진단이라 비교 기준(baseRunId)은 비어 있다.
    //        ※ 응답 코드: 201 = 새로 만들어짐
    @Test
    @DisplayName("업로드하면 trace.events에 trace.diagnostics.recorded가 계약 형식으로 나가고 message_id는 eventId다")
    void recordedEventIsPublished() throws Exception {
        Queue queue = new Queue("test.diagnostics.recorded", false, false, true);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("trace.events"))
                .with("trace.diagnostics.recorded"));

        String response = mvc.perform(post("/api/v1/diagnostics/runs").with(agent())
                .header("Idempotency-Key", "event-key-0001")
                .contentType(MediaType.APPLICATION_JSON).content(Examples.bytes(Examples.MACOS)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Message message = rabbit.receive(queue.getName(), 10_000);
        assertThat(message).isNotNull();
        JsonNode event = objectMapper.readTree(message.getBody());
        assertThat(EventSchemas.validateRecorded(event)).isEmpty();
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.get("eventId").asText());
        assertThat(event.get("runId").asText()).isEqualTo(objectMapper.readTree(response).get("runId").asText());
        assertThat(event.get("rentalId").asText()).isEqualTo(RENTAL);
        assertThat(event.get("checkpointId").asText()).isEqualTo(OUTBOUND);
        assertThat(event.get("baseRunId").isNull()).isTrue();
    }
}
