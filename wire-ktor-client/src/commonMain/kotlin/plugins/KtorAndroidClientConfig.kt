package com.douman.wire_ktor.plugins

import io.ktor.client.request.HttpRequestBuilder

///Plugin to be used by Android mobile app
class KtorAndroidClientConfig(
    private val packageName: String,
    private val cert: String,
): KtorClientPlugin {
    override fun onRequest(request: HttpRequestBuilder, content: Any) {
        request.headers.append("X-Android-Package", this.packageName)
        request.headers.append("X-Android-Cert", this.cert)
    }
}