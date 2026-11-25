package com.gakdo.trace.diagnostics.run.infra;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 소비 큐 trace.diagnostics.seals(contracts trace.record.sealed x-consumers, 11/13). 처리하지 못한 메시지는 trace.dlx → 우리 DLQ. */
@Configuration
public class SealAmqpConfig {

    public static final String QUEUE = "trace.diagnostics.seals";
    public static final String ROUTING_KEY = "trace.record.sealed";
    public static final String DLX = "trace.dlx";
    public static final String DEAD_KEY = "trace.diagnostics.seals.dead";
    public static final String DLQ = "trace.diagnostics.seals.dlq";

    @Bean
    Declarables sealTopology() {
        TopicExchange events = new TopicExchange(AmqpConfig.EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(DLX, true, false);
        Queue queue = QueueBuilder.durable(QUEUE).deadLetterExchange(DLX).deadLetterRoutingKey(DEAD_KEY).build();
        Queue dlq = QueueBuilder.durable(DLQ).build();
        Binding sealed = BindingBuilder.bind(queue).to(events).with(ROUTING_KEY);
        Binding dead = BindingBuilder.bind(dlq).to(dlx).with(DEAD_KEY);
        return new Declarables(dlx, queue, dlq, sealed, dead);
    }
}
