package com.douman.wire_ktor
import com.douman.wire_ktor.internal.extractHttpResponseHeaders
import com.douman.wire_ktor.internal.decodeHttpBody
import com.douman.wire_ktor.internal.checkHttpResponseForGrpcException
import com.douman.wire_ktor.internal.WireKtorContent

//part of wire
import okio.IOException
import okio.Timeout
import com.squareup.wire.GrpcCall
import com.squareup.wire.GrpcClient
import com.squareup.wire.GrpcException
import com.squareup.wire.GrpcMethod
import com.squareup.wire.GrpcStatus
import com.squareup.wire.GrpcStreamingCall
import com.squareup.wire.internal.ProtocolException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class WireKtorClient(
    private val client: HttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : GrpcClient() {
    override fun <S : Any, R : Any> newCall(method: GrpcMethod<S, R>): GrpcCall<S, R> {
        return WireKtorCall(method)
    }

    override fun <S : Any, R : Any> newStreamingCall(method: GrpcMethod<S, R>): GrpcStreamingCall<S, R> {
        throw UnsupportedOperationException("Analytics Ktor client does not support stream calls!")
    }

    inner class WireKtorCall<S : Any, R : Any>(
        override val method: GrpcMethod<S, R>,
    ) : GrpcCall<S, R> {
        private lateinit var response: Deferred<R>

        override val timeout: Timeout = Timeout.NONE

        override var requestMetadata: Map<String, String> = mapOf()

        override var responseMetadata: Map<String, String>? = null
            private set

        override fun cancel() = response.cancel()

        override fun clone(): GrpcCall<S, R> = WireKtorCall(method)

        override fun isCanceled(): Boolean = response.isCancelled

        override fun isExecuted(): Boolean = response.isCompleted

        override fun executeBlocking(request: S): R {
            return runBlocking {
                async { sendRequest(request) }.let {
                    response = it
                    it.await()
                }
            }
        }

        override suspend fun execute(request: S): R {
            scope.async { sendRequest(request) }.let {
                response = it
                return it.await()
            }
        }

        override fun enqueue(
            request: S,
            callback: GrpcCall.Callback<S, R>,
        ) {
            scope.launch {
                try {
                    callback.onSuccess(this@WireKtorCall, execute(request))
                } catch (error: IOException) {
                    callback.onFailure(this@WireKtorCall, error)
                }
            }
        }

        private fun buildRequest(request: S): HttpRequestBuilder {
            val grpcMethod = method
            return HttpRequestBuilder().apply {
                method = HttpMethod.Post
                url.encodedPath = grpcMethod.path

                accept(ContentType("application", "grpc"))
                requestMetadata.forEach {
                    header(it.key, it.value)
                }
                header("te", "trailers")
                //Explicitly state we do not expect compressed response
                header("grpc-accept-encoding", "identity")

                setBody(WireKtorContent(grpcMethod.requestAdapter, request))
                expectSuccess = false
            }
        }

        private suspend fun sendRequest(request: S): R {
            val httpRequest = buildRequest(request)
            val response = try {
                client.post(httpRequest)
            } catch (error: Exception) {
                throw GrpcException(GrpcStatus.UNAVAILABLE, "Send failed=${error.message}", null, httpRequest.url.toString())
            }

            responseMetadata = extractHttpResponseHeaders(response)
            val body = response.bodyAsChannel()
            checkHttpResponseForGrpcException(httpRequest, response)

            if (body.isClosedForRead) {
                throw GrpcException(GrpcStatus.INTERNAL, "No response body", null, httpRequest.url.toString())
            }

            try {
                return decodeHttpBody(method.responseAdapter, body)
            } catch (error: IOException) {
                throw GrpcException(GrpcStatus.INTERNAL, "Incomplete response=${error.message}", null, httpRequest.url.toString())
            }
        }
    }
}
