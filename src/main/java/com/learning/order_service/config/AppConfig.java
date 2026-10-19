package com.learning.order_service.config;

import com.learning.order_service.core.service.lifecycle.OrderLifecycleService;
import com.learning.order_service.core.usecase.CancelOrderUsecase;
import com.learning.order_service.core.usecase.GetOrderUsecase;
import com.learning.order_service.core.usecase.ListOrdersUsecase;
import com.learning.order_service.core.usecase.PayOrderUsecase;
import com.learning.order_service.core.usecase.PlaceOrderUsecase;
import com.learning.order_service.core.usecase.SettleOpenOrdersUsecase;
import com.learning.order_service.gateway.CartGateway;
import com.learning.order_service.gateway.InventoryGateway;
import com.learning.order_service.gateway.JpaOrderGateway;
import com.learning.order_service.gateway.OrderItemRepository;
import com.learning.order_service.gateway.OrderOutboxRepository;
import com.learning.order_service.gateway.OrderRepository;
import com.learning.order_service.gateway.OrderStatusHistoryRepository;
import com.learning.order_service.gateway.SagaStateRepository;
import com.learning.order_service.gateway.OrderGateway;
import com.learning.order_service.gateway.OrderSettlementJob;
import com.learning.order_service.gateway.PaymentGateway;
import com.learning.order_service.gateway.client.CartGatewayImpl;
import com.learning.order_service.gateway.client.InventoryGatewayImpl;
import com.learning.order_service.gateway.client.PaymentGatewayImpl;
import com.learning.order_service.core.usecase.HandleInventoryEventUsecase;
import com.learning.order_service.core.usecase.HandlePaymentEventUsecase;
import com.learning.order_service.gateway.InboxEventRepository;
import com.learning.order_service.gateway.OrderOutboxPoller;
import com.learning.order_service.gateway.messaging.EventPublisher;
import com.learning.order_service.gateway.messaging.KafkaEventPublisher;
import com.learning.order_service.gateway.messaging.LoggingEventPublisher;
import com.learning.order_service.gateway.messaging.InventoryEventListener;
import com.learning.order_service.gateway.messaging.PaymentEventListener;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.time.Duration;

/**
 * Wires the core layers by hand so that use case and service classes stay free of Spring stereotype annotations.
 */
@Configuration
@EnableConfigurationProperties(OrderProperties.class)
@EnableScheduling
public class AppConfig {

    // =========================================================================
    // Use cases
    // =========================================================================

    @Bean
    public PlaceOrderUsecase placeOrderUsecase(OrderGateway orders, CartGateway carts, InventoryGateway inventory,
                                               PaymentGateway payments, OrderProperties properties, Clock clock) {
        return new PlaceOrderUsecase(orders, carts, inventory, payments, properties, clock);
    }

    @Bean
    public PayOrderUsecase payOrderUsecase(OrderGateway orders, PaymentGateway payments, OrderLifecycleService lifecycle) {
        return new PayOrderUsecase(orders, payments, lifecycle);
    }

    @Bean
    public CancelOrderUsecase cancelOrderUsecase(OrderGateway orders, OrderLifecycleService lifecycle) {
        return new CancelOrderUsecase(orders, lifecycle);
    }

    @Bean
    public GetOrderUsecase getOrderUsecase(OrderGateway orders) {
        return new GetOrderUsecase(orders);
    }

    @Bean
    public ListOrdersUsecase listOrdersUsecase(OrderGateway orders) {
        return new ListOrdersUsecase(orders);
    }

    @Bean
    public SettleOpenOrdersUsecase settleOpenOrdersUsecase(OrderGateway orders, OrderLifecycleService lifecycle,
                                                           OrderProperties properties, Clock clock) {
        return new SettleOpenOrdersUsecase(orders, lifecycle, properties, clock);
    }

    @Bean
    public HandlePaymentEventUsecase handlePaymentEventUsecase(OrderGateway orders, OrderLifecycleService lifecycle,
                                                               JsonMapper json, Clock clock) {
        return new HandlePaymentEventUsecase(orders, lifecycle, json, clock);
    }

    @Bean
    public HandleInventoryEventUsecase handleInventoryEventUsecase(OrderGateway orders, InventoryGateway inventory,
                                                                   OrderLifecycleService lifecycle, JsonMapper json,
                                                                   Clock clock) {
        return new HandleInventoryEventUsecase(orders, inventory, lifecycle, json, clock);
    }

    // =========================================================================
    // Services
    // =========================================================================

    @Bean
    public OrderLifecycleService orderLifecycleService(OrderGateway orders, InventoryGateway inventory,
                                                       PaymentGateway payments, CartGateway carts, JsonMapper json,
                                                       Clock clock) {
        return new OrderLifecycleService(orders, inventory, payments, carts, json, clock);
    }

    // =========================================================================
    // Gateways
    // =========================================================================

    @Bean
    public OrderGateway orderGateway(OrderRepository orders, OrderItemRepository items,
                                     OrderStatusHistoryRepository history, SagaStateRepository sagas,
                                     OrderOutboxRepository outbox, InboxEventRepository inbox,
                                     PlatformTransactionManager transactionManager, JsonMapper json) {
        return new JpaOrderGateway(orders, items, history, sagas, outbox, inbox,
                new TransactionTemplate(transactionManager), json);
    }

    @Bean
    public CartGateway cartGateway(RestClient cartRestClient) {
        return new CartGatewayImpl(cartRestClient);
    }

    @Bean
    public InventoryGateway inventoryGateway(RestClient inventoryRestClient) {
        return new InventoryGatewayImpl(inventoryRestClient);
    }

    @Bean
    public PaymentGateway paymentGateway(RestClient paymentRestClient) {
        return new PaymentGatewayImpl(paymentRestClient);
    }

    @Bean
    @ConditionalOnProperty(name = "order.jobs.enabled", havingValue = "true", matchIfMissing = true)
    public OrderSettlementJob orderSettlementJob(SettleOpenOrdersUsecase usecase) {
        return new OrderSettlementJob(usecase);
    }

    @Bean
    @ConditionalOnProperty(name = "order.jobs.enabled", havingValue = "true", matchIfMissing = true)
    public OrderOutboxPoller orderOutboxPoller(OrderOutboxRepository outbox, EventPublisher publisher,
                                               PlatformTransactionManager transactionManager, Clock clock,
                                               @Value("${order.outbox.batch-size:100}") int batchSize) {
        return new OrderOutboxPoller(outbox, publisher, new TransactionTemplate(transactionManager), clock, batchSize);
    }

    // =========================================================================
    // Events
    // =========================================================================

    /** {@code order.events.publisher=kafka} sends to the broker; the default only logs. */
    @Bean
    @ConditionalOnProperty(name = "order.events.publisher", havingValue = "kafka")
    public EventPublisher kafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                              @Value("${order.events.topic:domain.order.events}") String topic) {
        return new KafkaEventPublisher(kafkaTemplate, topic);
    }

    @Bean
    @ConditionalOnProperty(name = "order.events.publisher", havingValue = "log", matchIfMissing = true)
    public EventPublisher loggingEventPublisher() {
        return new LoggingEventPublisher();
    }

    /** Off by default, so the service runs without a broker (the settlement job still settles by polling). */
    @Bean
    @ConditionalOnProperty(name = "order.events.consumer-enabled", havingValue = "true")
    public PaymentEventListener paymentEventListener(HandlePaymentEventUsecase usecase) {
        return new PaymentEventListener(usecase);
    }

    @Bean
    @ConditionalOnProperty(name = "order.events.consumer-enabled", havingValue = "true")
    public InventoryEventListener inventoryEventListener(HandleInventoryEventUsecase usecase) {
        return new InventoryEventListener(usecase);
    }

    /**
     * Creates the topics this service owns at startup, with explicit small partition counts so the cluster quota holds
     * (free tier: 10 topics, 64 partitions in total). Replication is left to the broker default.
     * Owns {@code domain.order.events} plus the dead-letter topics of the two it consumes.
     */
    @Bean
    @ConditionalOnExpression("('${order.events.publisher:log}' == 'kafka' or '${order.events.consumer-enabled:false}' == 'true')"
            + " and '${order.events.create-topics:true}' == 'true'")
    public KafkaAdmin.NewTopics orderEventTopics(
            @Value("${order.events.topic:domain.order.events}") String orderTopic,
            @Value("${order.events.payment-topic:domain.payment.events}") String paymentTopic,
            @Value("${order.events.inventory-topic:domain.inventory.events}") String inventoryTopic,
            @Value("${order.events.partitions:3}") int partitions) {
        return new KafkaAdmin.NewTopics(ownedTopics(orderTopic, paymentTopic, inventoryTopic, partitions)
                .toArray(NewTopic[]::new));
    }

    static List<NewTopic> ownedTopics(String orderTopic, String paymentTopic, String inventoryTopic, int partitions) {
        return List.of(
                TopicBuilder.name(orderTopic).partitions(partitions).build(),
                TopicBuilder.name(paymentTopic + ".dlq").partitions(1).build(),
                TopicBuilder.name(inventoryTopic + ".dlq").partitions(1).build());
    }

    /** Retries 50ms, 250ms, 1.25s (TDD §13.7), then parks the event on {@code <topic>.dlq} and moves on. */
    @Bean
    @ConditionalOnProperty(name = "order.events.consumer-enabled", havingValue = "true")
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(50);
        backOff.setMultiplier(5);
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, e) -> new TopicPartition(record.topic() + ".dlq", -1));
        return new DefaultErrorHandler(recoverer, backOff);
    }

    // =========================================================================
    // Infrastructure
    // =========================================================================

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RestClient cartRestClient(
            ObservationRegistry observationRegistry,
            @Value("${cart-service.base-url}") String baseUrl,
            @Value("${cart-service.connect-timeout}") Duration connectTimeout,
            @Value("${cart-service.read-timeout}") Duration readTimeout) {
        return restClient(observationRegistry, baseUrl, connectTimeout, readTimeout);
    }

    @Bean
    public RestClient inventoryRestClient(
            ObservationRegistry observationRegistry,
            @Value("${inventory-service.base-url}") String baseUrl,
            @Value("${inventory-service.connect-timeout}") Duration connectTimeout,
            @Value("${inventory-service.read-timeout}") Duration readTimeout) {
        return restClient(observationRegistry, baseUrl, connectTimeout, readTimeout);
    }

    @Bean
    public RestClient paymentRestClient(
            ObservationRegistry observationRegistry,
            @Value("${payment-service.base-url}") String baseUrl,
            @Value("${payment-service.connect-timeout}") Duration connectTimeout,
            @Value("${payment-service.read-timeout}") Duration readTimeout) {
        return restClient(observationRegistry, baseUrl, connectTimeout, readTimeout);
    }

    /** Built from Boot's RestClient.Builder so every call is timed as http.client.requests. */
    private static RestClient restClient(ObservationRegistry observationRegistry, String baseUrl, Duration connectTimeout, Duration readTimeout) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().observationRegistry(observationRegistry).baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
