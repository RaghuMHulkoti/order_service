package com.learning.order_service;

import com.learning.order_service.web.OrderController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots the real wiring (no Oracle: H2, Flyway and the settlement job off) to catch bean and property mistakes. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:wiring;MODE=Oracle",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.hikari.connection-test-query=SELECT 1",
        "spring.datasource.hikari.connection-init-sql=SELECT 1",
        "spring.flyway.enabled=false",
        "order.jobs.enabled=false"
})
class OrderServiceApplicationTests {

    @Autowired
    OrderController controller;

    @Test
    void contextLoads() {
        assertThat(controller).isNotNull();
    }
}
