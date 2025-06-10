package com.douman.wire_ktor.internal

import com.squareup.wire.GrpcException
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.GrpcStatus
import com.squareup.wire.MessageSink
import com.squareup.wire.MessageSource
import io.ktor.client.plugins.expectSuccess

import io.ktor.util.toMap
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readInt
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.runBlocking

/**
 * Verifies HttpResponse for presence of gRPC error, throwing GrpcException in case of unsuccessful response.
 */
@Throws(GrpcException::class)
internal fun checkHttpResponseForGrpcException(request: HttpRequestBuilder, response: HttpResponse) {
    val grpcStatus = response.headers["grpc-status"]?.let { GrpcStatus.get(it.toInt()) }

    if (grpcStatus == null) {
        val httpStatus = response.status
        throw GrpcException(GrpcStatus.INTERNAL, "Unexpected HTTP response status code=$httpStatus", null, request.url.toString())
    } else if (grpcStatus != GrpcStatus.OK) {
        val grpcMessage = response.headers["grpc-message"]
        throw GrpcException(grpcStatus, grpcMessage, null, request.url.toString())
    }
}

internal suspend fun <R : Any> decodeHttpBody(
    adapter: ProtoAdapter<R>,
    body: ByteReadChannel,
): R {
    val compressedFlag = body.readByte()
    if (compressedFlag != 0.toByte()) {
        throw UnsupportedOperationException("Response body is compressed!")
    }
    val length = body.readInt()
    val buffer = ByteArray(length)
    body.readAvailable(buffer)

    return adapter.decode(buffer)
}

internal fun prepareGrpcRequest(request: HttpRequestBuilder, path: String, requestMetadata: Map<String, String>) {
    request.method = HttpMethod.Post
    request.url.encodedPath = path

    request.accept(ContentType("application", "grpc"))
    request.header("te", "trailers")
    //Explicitly state we do not expect compressed response
    request.header("grpc-accept-encoding", "identity")
    requestMetadata.forEach {
        request.header(it.key, it.value)
    }
    request.expectSuccess = false
}

internal fun extractHttpResponseHeaders(response: HttpResponse): Map<String, String> {
    return response.headers.toMap().mapValues { (_, values) -> values.joinToString(", ") }
}

internal fun <S : Any> channelToMessageSink(chan: SendChannel<S>) = object : MessageSink<S> {
    override fun write(message: S) {
        runBlocking {
            chan.send(message)
        }
    }

    override fun cancel() {
        (chan as Channel<*>).cancel()
    }

    override fun close() {
        chan.close()
    }
}

internal fun <R : Any> channelToMessageSource(chan: ReceiveChannel<R>) = object : MessageSource<R> {
    override fun read(): R? {
        return runBlocking {
            chan.receiveCatching().onClosed { if (it != null) throw it }.getOrNull()
        }
    }

    override fun close() {
        (chan as Channel<*>).cancel()
    }
}