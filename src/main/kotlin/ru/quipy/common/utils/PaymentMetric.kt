package ru.quipy.common.utils

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class PaymentMetric(registry: MeterRegistry) {
    private val incomingCounter = Counter.builder("payments_incoming_total")
        .description("Total number of incoming payment requests")
        .register(registry)

    private val successCounter = Counter.builder("payments_success_total")
        .description("Total number of successful payments")
        .register(registry)

    private val failedCounter = Counter.builder("payments_failed_total")
        .description("Total number of failed payments")
        .register(registry)

    fun incoming() {
        incomingCounter.increment()
    }

    fun success() {
        successCounter.increment()
    }

    fun failed() {
        failedCounter.increment()
    }
}