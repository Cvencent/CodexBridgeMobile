package com.codexbridge.mobile.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The single HTTP stack for the whole app.
 *
 * Every [ApiClient] used to build its own [OkHttpClient], and the CBDP client
 * built a third one. Each instance owns its own connection pool, dispatcher and
 * idle-cleanup threads, and the app creates ApiClient often (route probing, the
 * notification service, re-pairing), so connection and thread count grew with
 * every call site.
 *
 * The three clients below are built once from the same base, so they share one
 * connection pool, one dispatcher and one pair of cleanup threads. The count is
 * fixed for the process lifetime and no longer scales with ApiClient instances.
 */
object SharedHttp {
    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Route probing must fail fast so the next candidate is tried quickly. */
    val probe: OkHttpClient = base.newBuilder()
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /** Event streams and the CBDP socket stay open until the peer closes. */
    val stream: OkHttpClient = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}
