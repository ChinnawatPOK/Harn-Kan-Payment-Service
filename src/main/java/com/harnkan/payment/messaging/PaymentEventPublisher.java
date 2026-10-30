package com.harnkan.payment.messaging;

import com.harnkan.payment.repository.PaymentRepository.PendingEvent;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventPublisher {
    public static final String EXCHANGE = "harnkan.events";
    private final RabbitTemplate rabbit;

    public PaymentEventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public void publish(PendingEvent event) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(event.eventId());
        properties.setType(event.routingKey());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        CorrelationData correlation = new CorrelationData(event.eventId());
        rabbit.setMandatory(true);
        rabbit.send(EXCHANGE, event.routingKey(),
                new Message(event.payload().getBytes(StandardCharsets.UTF_8), properties), correlation);
        if (!correlation.getFuture().get(5, TimeUnit.SECONDS).isAck() || correlation.getReturned() != null) {
            throw new IllegalStateException("Event was not routed and confirmed");
        }
    }

    @Configuration
    static class ExchangeConfiguration {
        @Bean
        TopicExchange paymentEventsExchange() {
            return new TopicExchange(EXCHANGE, true, false);
        }
    }
}
