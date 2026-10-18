package com.learning.order_service.core.usecase;

public interface Usecase<T extends UsecaseRequest, R extends UsecaseResponse> {
    R execute(T request);
}
