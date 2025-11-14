package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.MeterRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit


// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    val registry: MeterRegistry
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)

        val emptyBody = RequestBody.create(null, ByteArray(1))
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    private val client = OkHttpClient.Builder()
        .callTimeout(Duration.ofMillis(requestAverageProcessingTime.toMillis() * 2))
        .retryOnConnectionFailure(true)
        .build()

    // SETUP SOLUTION
    private val rateLimiter = SlidingWindowRateLimiter(
        rateLimitPerSec.toLong(),
        Duration.ofSeconds(1)
    )
    private val semaphore = Semaphore(parallelRequests)

    private val successCounter = Counter.builder("payments_success_total")
        .description("Total number of successful payments")
        .register(registry)

    private val failCounter = Counter.builder("payments_fail_total")
        .description("Total number of failed payments")
        .register(registry)

    private val retryCounter = Counter.builder("payments_retry_total")
        .description("Total number of retry")
        .register(registry)

    private val requestLatency = Timer.builder("requests_latency")
        .description("Request latency in seconds.")
        .publishPercentiles(0.5, 0.8, 0.99)
        .tags("component", "payment")
        .register(registry)


    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")

        val transactionId = UUID.randomUUID()

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }
        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")


        if (now() + requestAverageProcessingTime.toMillis() > deadline) {
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = "Request meets deadline")
            }
            return
        }


        val totalRetryTime = 3
        var retryTime = totalRetryTime
        var retry = true
        while (retry && retryTime > 0){
            retryTime--
            if (retryTime < totalRetryTime - 1) retryCounter.increment()

            rateLimiter.tickBlocking()
            semaphore.acquire()
            
            val startTime = System.currentTimeMillis()
            try {

                val request = Request.Builder().run {
                    url("http://$paymentProviderHostPort/external/process?serviceName=$serviceName&token=$token&accountName=$accountName&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
                    post(emptyBody)
                }.build()

                if (now() + requestAverageProcessingTime.toMillis() > deadline) {
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = "Request meets deadline")
                    }
                    return
                }

                client.newCall(request).execute().use { response ->
                    val body = try {
                        mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
                    } catch (e: Exception) {
                        logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                        ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                    }

                    if (body.result) {
                        successCounter.increment()
                    } else {
                        failCounter.increment()
                    }

                    logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

                    // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                    // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                    paymentESService.update(paymentId) {
                        it.logProcessing(body.result, now(), transactionId, reason = body.message)
                    }

                    if (body.result || now() + requestAverageProcessingTime.toMillis() > deadline){
                        retry = false
                    }
                }
            } catch (e: Exception) {
                when (e) {
                    is SocketTimeoutException -> {
                        logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
                        }
                    }

                    else -> {
                        logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)

                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now(), transactionId, reason = e.message)
                        }
                    }
                }
                failCounter.increment()
            } finally {
                semaphore.release()
                requestLatency.record(System.currentTimeMillis() - startTime, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

fun now() = System.currentTimeMillis()