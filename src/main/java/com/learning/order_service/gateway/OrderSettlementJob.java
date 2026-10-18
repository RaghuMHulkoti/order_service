package com.learning.order_service.gateway;

import com.learning.order_service.core.usecase.SettleOpenOrdersUsecase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Polls open orders so payments completed outside a customer request, and abandoned checkouts, are resolved. */
public class OrderSettlementJob {

    private static final Logger log = LoggerFactory.getLogger(OrderSettlementJob.class);
    private static final int BATCH = 100;

    private final SettleOpenOrdersUsecase usecase;

    public OrderSettlementJob(SettleOpenOrdersUsecase usecase) {
        this.usecase = usecase;
    }

    @Scheduled(fixedDelayString = "${order.jobs.settle-interval:30s}")
    public void run() {
        try {
            var result = usecase.execute(new SettleOpenOrdersUsecase.Request(BATCH));
            if (result.settled() > 0 || result.expired() > 0) {
                log.info("Settled {} order(s), expired {} unpaid order(s)", result.settled(), result.expired());
            }
        } catch (RuntimeException e) {
            log.warn("Order settlement run failed: {}", e.getMessage());
        }
    }
}
