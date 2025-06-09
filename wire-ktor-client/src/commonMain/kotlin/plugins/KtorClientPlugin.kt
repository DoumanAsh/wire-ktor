package com.douman.wire_ktor.plugins

import io.ktor.client.request.HttpRequestBuilder

interface KtorClientPlugin {
    fun onRequest(request: HttpRequestBuilder, content: Any);
}