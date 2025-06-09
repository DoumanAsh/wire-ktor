package com.douman.wire_ktor.plugins

import io.ktor.client.request.HttpRequestBuilder

class KtorClientApiConfig(
    private val key: String,
): KtorClientPlugin {
    override fun onRequest(request: HttpRequestBuilder, content: Any) {
        request.headers.append("X-api-key", this.key)
    }
}