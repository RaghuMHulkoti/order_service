package com.learning.order_service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Order Service API")
                        .description("Checkout saga and order lifecycle. Orders live in Oracle (schema ORDERS). "
                                + "Cart, Inventory and Payment are called over REST; this service never owns their data. "
                                + "The caller identity arrives in the X-User-Id header set by the API gateway.")
                        .version("1.0.0"));
    }
}
