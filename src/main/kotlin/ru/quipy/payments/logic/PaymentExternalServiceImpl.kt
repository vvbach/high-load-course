package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
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
        .version(HttpClient.Version.HTTP_2)
        .build()

    private val circuitBreaker = CircuitBreakerRegistry.of(
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50f)
            .slowCallRateThreshold(50f)
            .slowCallDurationThreshold(Duration.ofMillis(500))
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(20)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(3)
            .recordExceptions(
                java.io.IOException::class.java,
                java.net.http.HttpTimeoutException::class.java,
                java.util.concurrent.TimeoutException::class.java
            )
            .build()
    ).circuitBreaker(properties.accountName)

    private val esExecutor = Executors.newFixedThreadPool(16)

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

        process(paymentId, amount, transactionId, deadline)


    }

    private fun process(
        paymentId: UUID,
        amount: Int,
        transactionId: UUID,
        deadline: Long
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

        if (!circuitBreaker.tryAcquirePermission()) {
            semaphore.release()
            fail(paymentId, transactionId, "Circuit breaker is open")
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
                val duration = now() - startTime

                val body = try {
                    mapper.readValue(response.body(), ExternalSysResponse::class.java)
                } catch (e: Exception) {
                    circuitBreaker.onError(duration, TimeUnit.MILLISECONDS, e)
                    errorCounter.increment()
                    fail(paymentId, transactionId, e.message ?: "Invalid response")
                    return@thenAccept
                }

                if (body.result) {
                    circuitBreaker.onSuccess(duration, TimeUnit.MILLISECONDS)
                    successCounter.increment()
                } else {
                    circuitBreaker.onError(
                        duration,
                        TimeUnit.MILLISECONDS,
                        RuntimeException(body.message ?: "External payment failed")
                    )
                    errorCounter.increment()
                }

                updateAsync {
                    paymentESService.update(paymentId) {
                        it.logProcessing(body.result, now(), transactionId, body.message)
                    }
                }
            }
            .exceptionally { ex ->
                val duration = now() - startTime
                circuitBreaker.onError(duration, TimeUnit.MILLISECONDS, ex)
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