package internal

import com.douman.wire_ktor.wire_ktor_tests.proto.ProtoResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt

internal class GrpcResponse(private val result: Int, private val payload: String?): OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType("application", "grpc")
    override val headers: Headers = HeadersBuilder().apply {
        this.append("grpc-message", "test error")
        this.append("grpc-status", "$result")
    }.build()
    override val contentLength: Long?
        get() = null

    override fun trailers(): Headers {
        val builder = HeadersBuilder()
        builder.append("grpc-status", "$result")
        return builder.build()
    }

    override suspend fun writeTo(channel: ByteWriteChannel) {
        if (payload != null) {
            val response = ProtoResponse(payload)
            val adapter = ProtoResponse.ADAPTER
            channel.writeByte(0)
            channel.writeInt(adapter.encodedSize(response))
            channel.writeFully(adapter.encode(response))
        }
        channel.flushAndClose()
    }
}