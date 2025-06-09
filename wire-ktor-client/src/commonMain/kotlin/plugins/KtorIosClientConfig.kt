package com.douman.wire_ktor.plugins

import io.ktor.client.request.HttpRequestBuilder

///Plugin to be used by IOS mobile app
class KtorIosClientConfig(
    private val bundleId: String,
): KtorClientPlugin {
    override fun onRequest(request: HttpRequestBuilder, content: Any) {
        request.headers.append("X-Ios-Bundle-Identifier", this.bundleId)
    }
}