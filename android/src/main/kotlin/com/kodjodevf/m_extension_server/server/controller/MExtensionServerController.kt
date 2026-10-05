package m_extension_server.controller

import fi.iki.elonen.NanoHTTPD
import com.kodjodevf.m_extension_server.server.controller.DalvikHandler
import com.kodjodevf.m_extension_server.server.controller.ImageProxyHandler
import m_extension_server.impl.MihonImageProxy
import java.io.IOException

class MExtensionServerController(
) {
    private var server: WebServer? = null

    fun start(port: Int) {
        try {
            val listeningPort = if (port > 0) port else 8080
            server = WebServer(listeningPort)
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            val actualPort = server?.listeningPort ?: listeningPort
            MihonImageProxy.configure(if (actualPort > 0) actualPort else listeningPort)
        } catch (e: IOException) {
            throw e
        }
    }

    fun stop() {
        MihonImageProxy.clear()
        server?.stop()
    }

    fun isRunning(): Boolean = server?.isAlive == true

    private inner class WebServer(
        port: Int,
    ) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response =
            when {
                session.uri == "/dalvik" -> DalvikHandler().serve(session)
                session.uri.startsWith(ImageProxyHandler.ROUTE_PREFIX) -> ImageProxyHandler().serve(session)
                session.uri == "/" -> newFixedLengthResponse("MExtensionServer Server Running")
                session.uri == "/capabilities" ->
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        """{"mangayomiMihonBridge":1,"imageProxy":true}""",
                    )
                session.uri == "/stop" -> {
                    newFixedLengthResponse("Server stopping").also {
                        Thread {
                            Thread.sleep(100)
                            stop()
                        }.start()
                    }
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
    }
}
