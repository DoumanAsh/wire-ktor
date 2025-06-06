import kotlin.test.Test

import com.douman.wire_ktor.WireKtorClient
import io.ktor.client.HttpClient

class WireKtorClientTest {
    private val httpbin = "grpc://grpcb.in:9001"
    private val httpClient = HttpClient();

    @Test fun shouldExecuteSingleRequest() {
        val client = WireKtorClient(httpClient)
    }
}