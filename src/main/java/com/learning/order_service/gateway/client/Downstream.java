package com.learning.order_service.gateway.client;

import com.learning.order_service.exception.BusinessRuleViolationException;
import com.learning.order_service.exception.DependencyUnavailableException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Turns a failed downstream call into this service's two failure kinds: a 4xx is a rejection the caller can act on
 * (carrying the downstream error code), anything else means the service did not answer.
 */
final class Downstream {

    /** The error envelope all sibling services answer with: {@code {"body": {"errorCode", "message"}}}. */
    record ErrorEnvelope(ErrorBody body) {
    }

    record ErrorBody(String errorCode, String message) {
    }

    private Downstream() {
    }

    static RuntimeException failure(String service, RestClientException e) {
        if (e instanceof RestClientResponseException response && response.getStatusCode().is4xxClientError()) {
            try {
                var envelope = response.getResponseBodyAs(ErrorEnvelope.class);
                if (envelope != null && envelope.body() != null && envelope.body().errorCode() != null) {
                    return new BusinessRuleViolationException(envelope.body().errorCode(), envelope.body().message());
                }
            } catch (RuntimeException ignored) {
                // body was not the expected envelope; fall through to the generic rejection
            }
            return new BusinessRuleViolationException("DOWNSTREAM_REJECTED",
                    service + " rejected the request (" + response.getStatusCode().value() + ")");
        }
        return new DependencyUnavailableException(service + " unavailable", e);
    }
}
