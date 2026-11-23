package org.maplibre.navigation.android.navigation.ui.v5

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Receives log messages of a [RoutingClient]: every HTTP request with its duration and status,
 * failures and parsing time. Access tokens and API keys in urls are masked.
 *
 * Messages are passed from OkHttp threads, implementations must be thread safe.
 */
fun interface RoutingLogger {

    fun log(message: String)

    companion object {

        /**
         * Logs to Logcat. Long messages, e.g. response bodies, are split into several lines.
         */
        @JvmStatic
        @JvmOverloads
        fun logcat(tag: String = "MapLibreRouting", priority: Int = Log.DEBUG) = RoutingLogger { message ->
            message.chunked(LOGCAT_MAX_LENGTH).forEach { Log.println(priority, tag, it) }
        }

        private const val LOGCAT_MAX_LENGTH = 3_500
    }
}

internal class RoutingLoggingInterceptor(
    private val logger: RoutingLogger,
    private val logBodies: Boolean,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.redacted()
        val requestBody = request.body
        if (logBodies && requestBody != null) {
            val buffer = Buffer().also { requestBody.writeTo(it) }
            logger.log("--> ${request.method} $url\n${buffer.readUtf8().truncated()}")
        } else {
            logger.log("--> ${request.method} $url")
        }

        val startNs = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (exception: IOException) {
            logger.log("<-- FAILED ${request.method} $url after ${elapsedMs(startNs)} ms: $exception")
            throw exception
        }

        val size = response.body?.contentLength()?.takeIf { it >= 0 }?.let { " ${it / 1024} KB" }.orEmpty()
        val status = "<-- ${response.code} ${request.method} $url (${elapsedMs(startNs)} ms$size)"
        if (logBodies || !response.isSuccessful) {
            // Error bodies are always logged, they contain the reason of the failure
            val body = response.peekBody(MAX_PEEKED_BODY_BYTES).string()
            logger.log("$status\n${body.truncated()}")
        } else {
            logger.log(status)
        }
        return response
    }

    private fun elapsedMs(startNs: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

    private fun String.truncated(): String =
        if (length <= MAX_LOGGED_BODY_CHARS) this else take(MAX_LOGGED_BODY_CHARS) + "… (${length} chars)"

    private companion object {
        const val MAX_PEEKED_BODY_BYTES = 1024L * 1024L
        const val MAX_LOGGED_BODY_CHARS = 16_000
    }
}

private val SENSITIVE_QUERY_PARAMETERS = setOf("access_token", "key", "api_key", "apikey", "token")

internal fun HttpUrl.redacted(): HttpUrl {
    val sensitive = queryParameterNames.filter { it.lowercase() in SENSITIVE_QUERY_PARAMETERS }
    if (sensitive.isEmpty()) {
        return this
    }
    return newBuilder()
        .apply { sensitive.forEach { setQueryParameter(it, "***") } }
        .build()
}
