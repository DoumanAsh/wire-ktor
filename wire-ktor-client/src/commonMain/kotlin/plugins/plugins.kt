package com.douman.wire_ktor.plugins

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin

inline fun<reified T: KtorClientPlugin> ktor_client_plugin(plugin: T): ClientPlugin<Unit> {
    return createClientPlugin(T::class.simpleName ?: "Plugin") {
        onRequest { request, content ->
            plugin.onRequest(request, content)
        }
    }
}