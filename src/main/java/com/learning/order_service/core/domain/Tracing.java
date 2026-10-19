package com.learning.order_service.core.domain;

import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * The trace id carried in every event envelope (TDD §13.3). A handler continues the id of the event it is handling,
 * so one checkout keeps one id across services; otherwise the active OpenTelemetry trace is used, and a fresh id
 * when there is neither.
 */
public final class Tracing {

    private static final String KEY = "traceId";

    private Tracing() {
    }

    public static String current() {
        var inherited = MDC.get(KEY);
        if (inherited != null && !inherited.isBlank()) {
            return inherited;
        }
        var context = Span.current().getSpanContext();
        return context.isValid() ? context.getTraceId() : UUID.randomUUID().toString().replace("-", "");
    }

    /** Makes {@code traceId} the current id until the returned scope is closed; a blank id changes nothing. */
    public static Scope continueTrace(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return () -> { };
        }
        var previous = MDC.get(KEY);
        MDC.put(KEY, traceId);
        return () -> {
            if (previous == null) {
                MDC.remove(KEY);
            } else {
                MDC.put(KEY, previous);
            }
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
