package com.douman.wire_ktor.internal

import com.squareup.wire.GrpcException
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.GrpcStatus

import io.ktor.util.toMap
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readInt
import io.ktor.utils.io.readAvailable

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

internal fun extractHttpResponseHeaders(response: HttpResponse): Map<String, String> {
    return response.headers.toMap().mapValues { (_, values) -> values.joinToString(", ") }
}