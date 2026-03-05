package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    val registry: MeterRegistry
) : PaymentExternalSystemAdapter {

    companion object {
        private val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapterImpl::class.java)
        private val mapper = ObjectMapper().registerKotlinModule()
        private const val DEADLINE_HEADROOM_MS = 50L
    }

    private val semaphore = Semaphore(properties.parallelRequests)

    val rateLimiter = SlidingWindowRateLimiter(
        rate = properties.rateLimitPerSec.toLong(),
        window = Duration.ofSeconds(1)
    )

    private val httpClient = HttpClient.newBuilder()
        .executor(Executors.newFixedThreadPool(100))
        .version(HttpClient.Version.HTTP_2)
        .build()

    private val hedgeDelay = properties.averageProcessingTime.toMillis() / 2

    private val esExecutor = Executors.newFixedThreadPool(32)

    private val hedgeScheduler = Executors.newScheduledThreadPool(64)

    private val successCounter = Counter.builder("payment.success")
        .register(Metrics.globalRegistry)

    private val errorCounter = Counter.builder("payment.error")
        .register(Metrics.globalRegistry)

    private val latencyTimer = Timer.builder("payment.latency")
        .publishPercentiles(0.5, 0.9, 0.99)
        .register(Metrics.globalRegistry)

    override fun performPaymentAsync(
        paymentId: UUID,
        amount: Int,
        paymentStartedAt: Long,
        deadline: Long
    ) {
        val transactionId = UUID.randomUUID()

        updateAsync {
            paymentESService.update(paymentId) {
                it.logSubmission(true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
            }
        }
        val completed = AtomicBoolean(false)

        process(paymentId, amount, transactionId, deadline, completed)

        hedgeScheduler.schedule(
            {
                if (!completed.get()) {
                    process(paymentId, amount, transactionId, deadline, completed)
                }
            },
            hedgeDelay,
            TimeUnit.MILLISECONDS
        )
    }

    private fun process(
        paymentId: UUID,
        amount: Int,
        transactionId: UUID,
        deadline: Long,
        completed: AtomicBoolean
    ) {
        val remaining = deadline - now()

        if (remaining <= 0) {
            fail(paymentId, transactionId, "Deadline exceeded before admission")
            return
        }

        if (!rateLimiter.tickBlocking(remaining)) {
            fail(paymentId, transactionId, "Rate limit exceeded")
            return
        }

        if (!semaphore.tryAcquire(remaining, TimeUnit.MILLISECONDS)) {
            fail(paymentId, transactionId, "No capacity available")
            return
        }

        val httpTimeout = (deadline - now() - DEADLINE_HEADROOM_MS).coerceAtLeast(1)

        val request = HttpRequest.newBuilder()
            .uri(
                URI(
                    "http://$paymentProviderHostPort/external/process" +
                            "?serviceName=${properties.serviceName}" +
                            "&token=$token" +
                            "&accountName=${properties.accountName}" +
                            "&transactionId=$transactionId" +
                            "&paymentId=$paymentId" +
                            "&amount=$amount"
                )
            )
            .timeout(Duration.ofMillis(httpTimeout))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val startTime = now()

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { _, _ ->
                semaphore.release()
                latencyTimer.record(now() - startTime, TimeUnit.MILLISECONDS)
            }
            .thenAccept { response ->
                if (!completed.compareAndSet(false, true)) {
                    return@thenAccept
                }
                val body = try {
                    mapper.readValue(response.body(), ExternalSysResponse::class.java)
                } catch (e: Exception) {
                    ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                }

                updateAsync {
                    paymentESService.update(paymentId) {
                        it.logProcessing(body.result, now(), transactionId, body.message)
                    }
                }

                if (body.result) {
                    successCounter.increment()
                } else {
                    errorCounter.increment()
                }
            }
            .exceptionally { ex ->
                errorCounter.increment()
                fail(paymentId, transactionId, ex.message ?: "Unknown error")
                null
            }
    }

    private fun fail(paymentId: UUID, transactionId: UUID, reason: String) {
        updateAsync {
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason)
            }
        }
    }


    private fun updateAsync(task: () -> Unit) {
        esExecutor.submit(task)
    }

    override fun price() = properties.price
    override fun isEnabled() = properties.enabled
    override fun name() = properties.accountName
}

fun now(): Long = System.currentTimeMillis()