package m_extension_server.controller

import fi.iki.elonen.NanoHTTPD
import com.kodjodevf.m_extension_server.server.controller.DalvikHandler
import java.io.IOException

class MExtensionServerController(
) {
    private var server: WebServer? = null

    fun start(port: Int) {
        try {
            server = WebServer(port?:8080)
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (e: IOException) {
            throw e
        }
    }

    fun stop() {
        server?.stop()
    }

    fun isRunning(): Boolean = server?.isAlive == true

    private inner class WebServer(
        port: Int,
    ) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response =
            when (session.uri) {
                "/dalvik" -> DalvikHandler().serve(session)
                "/" -> newFixedLengthResponse("MExtensionServer Server Running")
                "/stop" -> {
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
