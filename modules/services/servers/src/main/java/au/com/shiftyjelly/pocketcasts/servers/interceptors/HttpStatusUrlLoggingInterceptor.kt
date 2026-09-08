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
            Timber.e(
                "HTTP %s %s %s",
                response.code,
                response.request.method,
                response.request.url,
            )
        }
        return response
    }
}
