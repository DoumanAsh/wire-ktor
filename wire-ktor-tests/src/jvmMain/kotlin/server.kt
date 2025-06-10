import com.douman.wire_ktor.wire_ktor_tests.proto.ProtoResponse

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
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
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.utils.io.discard

import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private val logger = KotlinLogging.logger {}

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

                logger.atInfo {
                    message = "POST $path"
                    payload = buildMap(capacity = 1) {
                        put("headers", headers.toString())
                    }
                }
                when(protoMethod) {
                    "ReturnError" -> {
                        val body = call.receive<ByteArray>()
                        call.respond(internal.GrpcResponse(13, null))
                    }
                    "ReturnSuccess" -> {
                        val body = call.receive<ByteArray>()
                        call.respond(internal.GrpcResponse(0, "1"))
                    }
                    "ReturnSuccessNoBody" -> {
                        val body = call.receive<ByteArray>()
                        call.respond(internal.GrpcResponse(0, null))
                    }
                    "ReturnListSuccess" -> {
                        val stream = call.receiveStream()
                        val body = ByteArray(1024)
                        stream.read(body)

                        logger.info { "Respond with stream" }
                        call.respond(internal.GrpcStreamResponse(0, listOf("1", "2")))
                    }
                    else -> call.respond(internal.GrpcResponse(12, null))
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