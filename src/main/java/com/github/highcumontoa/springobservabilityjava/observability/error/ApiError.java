package com.github.highcumontoa.springobservabilityjava.observability.error;

public record ApiError(String correlationId, String errorCategory, String reason) {
}
