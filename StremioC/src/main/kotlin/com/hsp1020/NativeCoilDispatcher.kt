package com.hsp1020

import android.content.Context
import coil3.SingletonImageLoader
import okhttp3.OkHttpClient

/** Apply the existing image concurrency policy without replacing the native Coil loader. */
internal object NativeCoilDispatcher {
    @Synchronized
    fun configure(context: Context) {
        try {
            val loader = SingletonImageLoader.get(context.applicationContext)
            val clients = ArrayList<OkHttpClient>()
            for ((factory, _) in loader.components.fetcherFactories) {
                if (!factory.javaClass.name.contains("NetworkFetcher")) continue
                try {
                    val lazyField = factory.javaClass.getDeclaredField("networkClientLazy")
                        .apply { isAccessible = true }
                    val networkClient = (lazyField.get(factory) as Lazy<*>).value ?: continue
                    val clientField = networkClient.javaClass.getDeclaredField("callFactory")
                        .apply { isAccessible = true }
                    val client = clientField.get(networkClient) as? OkHttpClient ?: continue
                    if (clients.any { it === client }) continue
                    clients.add(client)
                    client.dispatcher.maxRequests = 64
                    client.dispatcher.maxRequestsPerHost = 32
                } catch (_: Throwable) {
                    // Fail open when a different Coil fetcher implementation is installed.
                }
            }
        } catch (_: Throwable) {
            // Never block plugin startup if the native image loader is unavailable.
        }
    }
}
