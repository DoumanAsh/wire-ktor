import kotlin.test.Test

import com.douman.wire_ktor.WireKtorClient
import com.douman.wire_ktor.wire_ktor_tests.proto.GrpcTestServiceClient
import com.douman.wire_ktor.wire_ktor_tests.proto.ProtoRequest
import com.douman.wire_ktor.plugins.ktor_client_plugin
import com.douman.wire_ktor.plugins.KtorClientApiConfig
import com.squareup.wire.GrpcException
import com.squareup.wire.GrpcStatus
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.URLProtocol
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.assertEquals

val PORT = 9091

class WireKtorClientTest {
    private val logger = KotlinLogging.logger {}

    private val httpClient = HttpClient(OkHttp) {
        install(Logging) {
            level = LogLevel.HEADERS
        }
        install(ktor_client_plugin(KtorClientApiConfig("test key")))
        defaultRequest {
            url {
                protocol = URLProtocol.HTTPS
                host = "localhost"
                port = PORT
            }
        }
        engine {
            config {
                sslSocketFactory(mockTlsContext().socketFactory, MockTrustManager())
            }
        }
    }
    private val grpcClient = GrpcTestServiceClient(WireKtorClient(httpClient))

    companion object {
        private val config = Config(
            port = 9090,
            tlsPort = PORT
        )

        private val server = server(config);
        @BeforeClass @JvmStatic fun setup() {
            server.start(wait = false)
        }

        @AfterClass @JvmStatic fun teardown() {
            server.stop()
        }
    }

    @Test fun shouldHandleSingleProperError() {
        val request = ProtoRequest("ping")

        try {
            grpcClient.ReturnError().executeBlocking(request)
        } catch (error: GrpcException) {
            assertEquals(error.grpcStatus, GrpcStatus.INTERNAL)
        }
    }

    @Test fun shouldHandleSingleOk() {
        val request = ProtoRequest("ping")

        val result = grpcClient.ReturnSuccess().executeBlocking(request)
        assertEquals(result.pong, "1")
    }

    @Test fun shouldHandleSingleOkWithoutBody() {
        val request = ProtoRequest("ping")

        try {
            grpcClient.ReturnSuccessNoBody().executeBlocking(request)
        } catch (error: GrpcException) {
            assertEquals(error.grpcStatus, GrpcStatus.INTERNAL)
        }
    }
}