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
                                               PaymentGateway payments, OrderLifecycleService lifecycle,
                                               OrderProperties properties, Clock clock) {
        return new PlaceOrderUsecase(orders, carts, inventory, payments, lifecycle, properties, clock);
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
                                     OrderOutboxRepository outbox, PlatformTransactionManager transactionManager,
                                     JsonMapper json) {
        return new JpaOrderGateway(orders, items, history, sagas, outbox, new TransactionTemplate(transactionManager), json);
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

    // =========================================================================
    // Infrastructure
    // =========================================================================

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RestClient cartRestClient(
            @Value("${cart-service.base-url}") String baseUrl,
            @Value("${cart-service.connect-timeout}") Duration connectTimeout,
            @Value("${cart-service.read-timeout}") Duration readTimeout) {
        return restClient(baseUrl, connectTimeout, readTimeout);
    }

    @Bean
    public RestClient inventoryRestClient(
            @Value("${inventory-service.base-url}") String baseUrl,
            @Value("${inventory-service.connect-timeout}") Duration connectTimeout,
            @Value("${inventory-service.read-timeout}") Duration readTimeout) {
        return restClient(baseUrl, connectTimeout, readTimeout);
    }

    @Bean
    public RestClient paymentRestClient(
            @Value("${payment-service.base-url}") String baseUrl,
            @Value("${payment-service.connect-timeout}") Duration connectTimeout,
            @Value("${payment-service.read-timeout}") Duration readTimeout) {
        return restClient(baseUrl, connectTimeout, readTimeout);
    }

    private static RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
