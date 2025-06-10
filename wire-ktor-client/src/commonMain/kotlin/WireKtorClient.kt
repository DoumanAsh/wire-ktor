package com.douman.wire_ktor
import com.douman.wire_ktor.internal.extractHttpResponseHeaders
import com.douman.wire_ktor.internal.decodeHttpBody
import com.douman.wire_ktor.internal.checkHttpResponseForGrpcException
import com.douman.wire_ktor.internal.WireKtorContent
import com.douman.wire_ktor.internal.WireKtorContents
import com.douman.wire_ktor.internal.prepareGrpcRequest
import com.douman.wire_ktor.internal.channelToMessageSink
import com.douman.wire_ktor.internal.channelToMessageSource

//part of wire
import okio.IOException
import okio.Timeout
import com.squareup.wire.GrpcCall
import com.squareup.wire.GrpcClient
import com.squareup.wire.GrpcException
import com.squareup.wire.GrpcMethod
import com.squareup.wire.GrpcStatus
import com.squareup.wire.GrpcStreamingCall
import com.squareup.wire.MessageSink
import com.squareup.wire.MessageSource
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

class WireKtorClient(
    private val client: HttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : GrpcClient() {
    override fun <S : Any, R : Any> newCall(method: GrpcMethod<S, R>): GrpcCall<S, R> {
        return WireKtorCall(method)
    }

    override fun <S : Any, R : Any> newStreamingCall(method: GrpcMethod<S, R>): GrpcStreamingCall<S, R> {
        return WireKtorStreamingCall(method)
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
                prepareGrpcRequest(this, grpcMethod.path, requestMetadata)

                setBody(WireKtorContent(grpcMethod.requestAdapter, request))
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
    } //WireKtorCall


    inner class WireKtorStreamingCall<S : Any, R : Any>(
        override val method: GrpcMethod<S, R>,
    ) : GrpcStreamingCall<S, R> {
        private lateinit var response: Deferred<R>
        private lateinit var job: Job

        override val timeout: Timeout = Timeout.NONE

        override var requestMetadata: Map<String, String> = mapOf()

        override var responseMetadata: Map<String, String>? = null
            private set

        override fun cancel() {
            job.cancel()
            response.cancel()
        }

        override fun clone(): GrpcStreamingCall<S, R> = WireKtorStreamingCall(method)

        override fun isCanceled(): Boolean = response.isCancelled

        override fun isExecuted(): Boolean = response.isCompleted

        private fun buildRequest(requests: Channel<S>): HttpRequestBuilder {
            val grpcMethod = method
            return HttpRequestBuilder().apply {
                prepareGrpcRequest(this, grpcMethod.path, requestMetadata)

                setBody(WireKtorContents(grpcMethod.requestAdapter, requests))
            }
        }

        override fun executeIn(scope: CoroutineScope): Pair<SendChannel<S>, ReceiveChannel<R>> {
            val requests = Channel<S>(1)
            val responses = Channel<R>(1)

            job =
                scope.launch {
                    val httpRequest = buildRequest(requests)
                    val response = client.post(httpRequest)

                    responseMetadata = extractHttpResponseHeaders(response)
                    val body = response.bodyAsChannel()
                    checkHttpResponseForGrpcException(httpRequest, response)

                    try {
                        while (!body.isClosedForRead) {
                            responses.send(decodeHttpBody(method.responseAdapter, body))
                        }
                    } catch (error: IOException) {
                        throw GrpcException(GrpcStatus.INTERNAL, "Unexpected stream error=${error.message}", null, httpRequest.url.toString())
                    }
                }

            job.invokeOnCompletion {
                requests.close()
                responses.close()
            }
            return requests to responses
        }

        @Deprecated(
            "Provide a scope, preferably not GlobalScope",
            replaceWith = ReplaceWith("executeIn(GlobalScope)", "kotlinx.coroutines.GlobalScope"),
            level = DeprecationLevel.WARNING,
        )
        override fun execute(): Pair<SendChannel<S>, ReceiveChannel<R>> = executeIn(scope)

        override fun executeBlocking(): Pair<MessageSink<S>, MessageSource<R>> {
            val result = executeIn(scope)
            return channelToMessageSink(result.first) to channelToMessageSource(result.second)
        }
    } // WireKtorStreamingCall
}
