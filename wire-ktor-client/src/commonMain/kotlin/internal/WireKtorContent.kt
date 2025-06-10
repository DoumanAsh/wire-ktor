package com.douman.wire_ktor.internal

import com.squareup.wire.ProtoAdapter
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach

class WireKtorContent<S: Any>(
    private val adapter: ProtoAdapter<S>,
    private val request: S,
    ): OutgoingContent.WriteChannelContent() {

    override val contentType: ContentType = ContentType("application", "grpc")

    override suspend fun writeTo(channel: ByteWriteChannel) {
        channel.writeByte(0)
        channel.writeInt(adapter.encodedSize(request))
        channel.writeFully(adapter.encode(request))
    }
}

class WireKtorContents<S: Any>(
    private val adapter: ProtoAdapter<S>,
    private val requests: Channel<S>,
): OutgoingContent.WriteChannelContent() {

    override val contentType: ContentType = ContentType("application", "grpc")

    override suspend fun writeTo(channel: ByteWriteChannel) {
        requests.consumeEach { request ->
            channel.writeByte(0)
            channel.writeInt(adapter.encodedSize(request))
            channel.writeFully(adapter.encode(request))
        }
    }
}