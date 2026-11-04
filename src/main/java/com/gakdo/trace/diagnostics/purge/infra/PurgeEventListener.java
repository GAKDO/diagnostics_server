package com.gakdo.trace.diagnostics.purge.infra;

import java.io.IOException;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.diagnostics.purge.application.RunPurgeService;

/**
 * trace.checkpoint.purged 소비. 필요한 필드만 읽는다(너그러운 소비자). 시리얼이 TEST-가 아니거나 형식이 깨졌으면 지우지 않고,
 * 이 서버에는 리스너 재시도 설정이 없으므로 처리 실패도 다시 넣지 않고 DLX로 보낸다(무한 재전달 방지).
 */
@Component
public class PurgeEventListener {

    private static final String PUBLIC_ID = "^[A-Za-z0-9_-]{21}$";
    private static final String TEST_SERIAL_PREFIX = "TEST-";

    private final RunPurgeService service;
    private final ObjectMapper mapper;

    public PurgeEventListener(RunPurgeService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @RabbitListener(queues = PurgeAmqpConfig.QUEUE)
    public void onMessage(Message message) {
        handle(message.getBody());
    }

    public int handle(byte[] body) {
        JsonNode event;
        try {
            event = mapper.readTree(body);
        } catch (IOException e) {
            throw new AmqpRejectAndDontRequeueException("시험 시점 지움 이벤트 JSON을 읽을 수 없어 DLX로 보냅니다", e);
        }
        String rentalId = event.path("rentalId").asText("");
        String checkpointId = event.path("checkpointId").asText("");
        String serial = event.path("serial").asText("");
        if (!rentalId.matches(PUBLIC_ID) || !checkpointId.matches(PUBLIC_ID) || !serial.startsWith(TEST_SERIAL_PREFIX)) {
            throw new AmqpRejectAndDontRequeueException("시험 데이터가 아니거나 형식이 맞지 않아 지우지 않고 DLX로 보냅니다");
        }
        try {
            return service.purge(rentalId, checkpointId);
        } catch (RuntimeException e) {
            throw new AmqpRejectAndDontRequeueException("시험 시점 진단을 지우지 못해 DLX로 보냅니다 checkpoint=" + checkpointId, e);
        }
    }
}
