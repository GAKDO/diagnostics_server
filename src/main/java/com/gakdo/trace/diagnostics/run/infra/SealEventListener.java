package com.gakdo.trace.diagnostics.run.infra;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.diagnostics.common.infra.PublicIds;
import com.gakdo.trace.diagnostics.run.application.SealedCheckpointStore;

/**
 * trace.record.sealed 소비(11/13). 봉투의 checkpointId와 봉인 시각(signedAt, 없으면 occurredAt)만 읽는다(너그러운 소비자).
 * 형식이 깨진 메시지는 다시 넣어도 소용없어 바로 DLX로 보낸다.
 */
@Component
public class SealEventListener {

    private static final Logger log = LoggerFactory.getLogger(SealEventListener.class);

    private final SealedCheckpointStore seals;
    private final ObjectMapper mapper;

    public SealEventListener(SealedCheckpointStore seals, ObjectMapper mapper) {
        this.seals = seals;
        this.mapper = mapper;
    }

    @RabbitListener(queues = SealAmqpConfig.QUEUE)
    public void onMessage(Message message) {
        handle(message.getBody());
    }

    /** @return 새로 기록했으면 true(같은 봉인을 두 번 받으면 false) */
    public boolean handle(byte[] body) {
        JsonNode event;
        try {
            event = mapper.readTree(body);
        } catch (IOException e) {
            throw new AmqpRejectAndDontRequeueException("봉인 이벤트 JSON을 읽을 수 없어 DLX로 보냅니다", e);
        }
        String checkpointId = event == null ? "" : event.path("checkpointId").asText("");
        if (!PublicIds.isValid(checkpointId)) {
            throw new AmqpRejectAndDontRequeueException("봉인 이벤트의 checkpointId 형식이 맞지 않아 DLX로 보냅니다");
        }
        OffsetDateTime sealedAt = time(event, "signedAt");
        if (sealedAt == null) {
            sealedAt = time(event, "occurredAt");
        }
        if (sealedAt == null) {
            throw new AmqpRejectAndDontRequeueException("봉인 이벤트에 시각이 없어 DLX로 보냅니다 checkpoint=" + checkpointId);
        }
        boolean added = seals.markSealed(checkpointId, sealedAt);
        log.info("봉인된 시점을 기록했습니다: checkpoint={}{}", checkpointId, added ? "" : " (이미 있음)");
        return added;
    }

    private static OffsetDateTime time(JsonNode event, String field) {
        String text = event.path(field).asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
