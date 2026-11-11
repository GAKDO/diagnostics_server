package com.gakdo.trace.diagnostics.run.infra;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.gakdo.trace.diagnostics.run.application.DiagnosticsEventPublisher;
import com.gakdo.trace.diagnostics.run.application.DiagnosticsRecordedEvent;

/** 메시지 ID = eventId(소비자가 멱등 처리에 쓴다). 영속 메시지(Spring AMQP 기본). */
@Component
public class RabbitDiagnosticsEventPublisher implements DiagnosticsEventPublisher {

    private final RabbitTemplate rabbit;

    public RabbitDiagnosticsEventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Override
    public void publishRecorded(DiagnosticsRecordedEvent event) {
        rabbit.convertAndSend(AmqpConfig.EXCHANGE, DiagnosticsRecordedEvent.ROUTING_KEY, event, message -> {
            message.getMessageProperties().setMessageId(event.eventId());
            return message;
        });
    }
}
