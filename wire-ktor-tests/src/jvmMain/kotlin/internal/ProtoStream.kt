package internal

import com.douman.wire_ktor.wire_ktor_tests.proto.ProtoResponse
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readInt
import kotlinx.coroutines.channels.ChannelIterator

class ProtoStream(val input: ByteReadChannel): ChannelIterator<ProtoResponse> {
    private val adapter = ProtoResponse.ADAPTER
    private var pending: ProtoResponse? = null

    override suspend fun hasNext(): Boolean {
        if (this.pending != null) {
            throw IllegalStateException("next() is not called");
        } else if (input.isClosedForRead) {
            return false;
        }
        val flag = input.readByte();
        if (flag != 0.toByte()) {
            throw Exception("Compressed data not supported");
        }
        val len = input.readInt();
        val buffer = ByteArray(len)
        input.readAvailable(buffer)

        this.pending = adapter.decode(buffer);
        return true;
    }

    override fun next(): ProtoResponse {
        val result = this.pending;
        if (result == null) {
            throw IllegalStateException("hasNext() is not called");
        } else {
            this.pending = null
            return result;
        }
    }
}