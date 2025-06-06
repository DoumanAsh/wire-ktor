import com.douman.wire_ktor.wire_ktor_tests.proto.ProtoResponse

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.content.OutgoingContent
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.server.application.Application
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.routing.*
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.jetty.jakarta.JettyApplicationEngine
import io.ktor.server.jetty.jakarta.JettyApplicationEngineBase
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.discard
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt

import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private val logger = KotlinLogging.logger {}

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
    }
}

data class Config(
    val port: Int,
    val tlsPort: Int,
)

class MockTrustManager: X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate?> = arrayOfNulls(0)
    override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
    override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {}
}

fun mockTlsContext(): SSLContext {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf(MockTrustManager()), null)
    return sslContext
}

fun createKeystore(): KeyStore {
    val keyStore = buildKeyStore {
        certificate("localhost") {
            password = ""
            domains = listOf("127.0.0.1", "0.0.0.0", "localhost")
        }
    }
    return keyStore;
}

fun Application.module() {
    routing {
        post("com.douman.wire_ktor.wire_ktor_tests.proto.TestService/{method}") {
            val path = call.request.path()
            try {
                val protoMethod = call.pathParameters["method"]
                val headers = call.request.headers;
                val body = call.receive<ByteArray>()
                val bodyLen = body.size

                logger.atInfo {
                    message = "POST $path"
                    payload = buildMap(capacity = 2) {
                        put("body_len", bodyLen)
                        put("headers", headers.toString())
                    }
                }
                when(protoMethod) {
                    "ReturnError" -> call.respond(GrpcResponse(13, null))
                    "ReturnSuccess" -> call.respond(GrpcResponse(0, "1"))
                    "ReturnSuccessNoBody" -> call.respond(GrpcResponse(0, null))
                    else -> call.respond(GrpcResponse(12, null))
                }
            } catch (error: Exception) {
                logger.atWarn {
                    message = "POST $path FAILED"
                    cause = error
                }
            } finally {
                call.request.receiveChannel().discard()
            }
        }
    }
}

fun server(config: Config): EmbeddedServer<JettyApplicationEngine, JettyApplicationEngineBase.Configuration> {
    val env = applicationEnvironment {
    }

    val server = embeddedServer(Jetty, env, {
        envConfig(config)
    }, module = Application::module)
    return server
}

private fun ApplicationEngine.Configuration.envConfig(config: Config) {
    connector {
        port = config.port
    }
    sslConnector(
        keyStore = createKeystore(),
        keyAlias = "localhost",
        keyStorePassword = { "".toCharArray() },
        privateKeyPassword = { "".toCharArray() },
        {
            port = config.tlsPort
        }
    )
}