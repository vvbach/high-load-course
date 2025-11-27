package ru.quipy.payments.logic

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.common.utils.*
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.exception.TooManyRequestsException
import ru.quipy.payments.logic.PaymentExternalSystemAdapterImpl.Companion
import java.time.Duration
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Service
class OrderPayer(
    @Autowired registry: MeterRegistry
) {

    companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderPayer::class.java)
    }

    @Autowired
    private lateinit var paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>

    @Autowired
    private lateinit var paymentService: PaymentService

    private val incomingCounter = Counter.builder("payments_incoming_total")
        .description("Total number of incoming payment requests")
        .register(registry)

    private val cancelCounter = Counter.builder("payments_cancel_total")
        .description("Total number of canceled payments")
        .register(registry)

    private val queue = LinkedBlockingQueue<Runnable>(8000)

    private val paymentExecutor = ThreadPoolExecutor(
        50,
        50,
        5L,
        TimeUnit.MILLISECONDS,
        queue,
        NamedThreadFactory("payment-submission-executor"),
        CallerBlockingRejectedExecutionHandler()
    )

    init {
        Gauge.builder("payments_queue_size", queue) { it.size.toDouble() }
            .description("Current number of payment tasks in the executor queue")
            .register(registry)
    }

    fun processPayment(orderId: UUID, amount: Int, paymentId: UUID, deadline: Long): Long {
        val createdAt = System.currentTimeMillis()

        incomingCounter.increment()

        val task = Runnable {
            val createdEvent = paymentESService.create {
                it.create(
                    paymentId,
                    orderId,
                    amount
                )
            }
            logger.info("Payment ${createdEvent.paymentId} for order $orderId created.")

            paymentService.submitPaymentRequest(paymentId, amount, createdAt, deadline)
        }


        if (!queue.offer(task)){
            logger.error("Payment $paymentId for order $orderId rejected because the queue is full!")
            cancelCounter.increment()
            throw TooManyRequestsException("Too many payment requests")
        } else {
            paymentExecutor.execute(task)
        }
        return createdAt
    }
}