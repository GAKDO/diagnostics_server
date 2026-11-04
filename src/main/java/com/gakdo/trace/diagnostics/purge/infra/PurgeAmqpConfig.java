package com.gakdo.trace.diagnostics.purge.infra;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gakdo.trace.diagnostics.run.infra.AmqpConfig;

/** 소비 큐 trace.diagnostics.purged(contracts trace.checkpoint.purged x-consumers). 처리하지 못한 메시지는 trace.dlx → 우리 DLQ. */
@Configuration
public class PurgeAmqpConfig {

    public static final String QUEUE = "trace.diagnostics.purged";
    public static final String ROUTING_KEY = "trace.checkpoint.purged";
    public static final String DLX = "trace.dlx";
    public static final String DEAD_KEY = "trace.diagnostics.purged.dead";
    public static final String DLQ = "trace.diagnostics.purged.dlq";

    @Bean
    Declarables purgeTopology() {
        TopicExchange events = new TopicExchange(AmqpConfig.EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(DLX, true, false);
        Queue queue = QueueBuilder.durable(QUEUE).deadLetterExchange(DLX).deadLetterRoutingKey(DEAD_KEY).build();
        Queue dlq = QueueBuilder.durable(DLQ).build();
        Binding purged = BindingBuilder.bind(queue).to(events).with(ROUTING_KEY);
        Binding dead = BindingBuilder.bind(dlq).to(dlx).with(DEAD_KEY);
        return new Declarables(dlx, queue, dlq, purged, dead);
    }
}
