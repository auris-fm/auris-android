package au.com.shiftyjelly.pocketcasts.servers.interceptors

import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber

/**
 * Logs method + full URL on non-2xx responses so Retrofit `HttpException`
 * stacks are diagnosable without relying on OkHttp BODY logging.
 */
class HttpStatusUrlLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) {
            // 5xx is a genuine failure; expected 4xx control flow (404
            // "no fingerprint reference", CDN misses) must not pollute
            // error reporting — debug only.
            val message = "HTTP %s %s %s"
            if (response.code >= 500) {
                Timber.e(message, response.code, response.request.method, response.request.url)
            } else {
                Timber.d(message, response.code, response.request.method, response.request.url)
            }
        }
        return response
    }
}
