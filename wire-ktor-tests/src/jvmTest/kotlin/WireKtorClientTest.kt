import kotlin.test.Test

import com.douman.wire_ktor.WireKtorClient
import com.douman.wire_ktor.wire_ktor_tests.proto.GrpcTestServiceClient
import com.douman.wire_ktor.wire_ktor_tests.proto.ProtoRequest
import com.douman.wire_ktor.plugins.ktor_client_plugin
import com.douman.wire_ktor.plugins.KtorClientApiConfig

import io.github.oshai.kotlinlogging.KotlinLogging
import com.squareup.wire.GrpcException
import com.squareup.wire.GrpcStatus
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.URLProtocol
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    @Test fun shouldHandleMultiOk() {
        val request = ProtoRequest("ping")

        val result = grpcClient.ReturnListSuccess().executeBlocking()
        result.first.write(request)
        result.first.close()

        logger.info { "Read first response" }
        val response1 = result.second.read()
        logger.info { "Read second response" }
        val response2 = result.second.read()
        logger.info { "Read third response" }
        val response3 = result.second.read()
        assertNotNull(response1)
        assertEquals(response1.pong, "1")
        assertNotNull(response2)
        assertEquals(response2.pong, "2")
        assertNull(response3)
    }
}