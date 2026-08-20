package com.kodjodevf.m_extension_server.server.controller

import android.util.Log
import m_extension_server.impl.MExtensionServerLoader
import m_extension_server.impl.MihonInvoker
import m_extension_server.model.DataBody
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.online.HttpSource
import fi.iki.elonen.NanoHTTPD
import okhttp3.Cookie
import okhttp3.HttpUrl
import java.net.URI

class DalvikHandler {
    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response =
        try {
            // Parse JSON body first to get extension data
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            val json = body["postData"] ?: throw IllegalArgumentException("No JSON body")

            // Deserialize DataBody
            val dataBody = objectMapper.readValue(json, DataBody::class.java)

            // Invoke with cached extension
            val result =
                MExtensionServerLoader.invokeWithExtension(dataBody.data) { loadedExtension ->
                    val source =
                        loadedExtension.source
                            ?: throw IllegalArgumentException("No source found in extension")

                    // Get domain from source
                    val domain =
                        try {
                            val baseUrl =
                                when (source) {
                                    is HttpSource -> source.baseUrl
                                    is AnimeHttpSource -> source.baseUrl
                                    else -> source.javaClass.getMethod("getBaseUrl").invoke(source) as? String
                                }
                            if (baseUrl != null) URI(baseUrl).host else "localhost"
                        } catch (e: Exception) {
                            Log.w("DalvikHandler", "Could not get baseUrl from source: ${e.message}")
                            "localhost"
                        } ?: "localhost"

                    // Intercept Cookie header and save to global cookie jar
                    val cookies =
                        (session.headers["cookie"] ?: session.headers["Cookie"])
                            ?.let { cookieHeader ->
                                cookieHeader.split(";").mapNotNull { cookieStr ->
                                    val trimmed = cookieStr.trim()
                                    val parts = trimmed.split("=", limit = 2)
                                    if (parts.size == 2) {
                                        val name = parts[0].trim()
                                        val value = parts[1].trim()
                                        Cookie
                                            .Builder()
                                            .name(name)
                                            .value(value)
                                            .domain(domain.removePrefix("."))
                                            .path("/")
                                            .build()
                                    } else {
                                        null
                                    }
                                }
                            }?.toList()

                    val network =
                        when (source) {
                            is HttpSource -> source.network
                            is AnimeHttpSource -> source.network
                            else -> null
                        }

                    if (cookies != null && cookies.isNotEmpty()) {
                        network?.cookieJar?.addAll(
                            HttpUrl
                                .Builder()
                                .scheme("http")
                                .host(domain.removePrefix("."))
                                .build(),
                            cookies,
                        )
                    }
                    val ua = session.headers["user-agent"] ?: session.headers["User-Agent"]
                    if (ua != null) {
                        network?.setUA(ua)
                    }

                    MihonInvoker.invokeMethod(source, dataBody)
                }

            // Serialize response
            val responseJson = objectMapper.writeValueAsString(result)

            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                responseJson,
            )
        } catch (e: Throwable) {
            Log.e("DalvikHandler", "Error handling request: ${e.message}", e)
            val status =
                when (e) {
                    is HttpException -> {
                        when (e.code) {
                            400 -> NanoHTTPD.Response.Status.BAD_REQUEST
                            401 -> NanoHTTPD.Response.Status.UNAUTHORIZED
                            403 -> NanoHTTPD.Response.Status.FORBIDDEN
                            404 -> NanoHTTPD.Response.Status.NOT_FOUND
                            429 -> NanoHTTPD.Response.Status.INTERNAL_ERROR
                            500 -> NanoHTTPD.Response.Status.INTERNAL_ERROR
                            else -> NanoHTTPD.Response.Status.INTERNAL_ERROR
                        }
                    }
                    is IllegalArgumentException -> NanoHTTPD.Response.Status.BAD_REQUEST
                    else -> NanoHTTPD.Response.Status.INTERNAL_ERROR
                }
            val errorMessage = e.message ?: e.cause?.message ?: e.javaClass.simpleName
            val errorResponse =
                mapOf(
                    "error" to errorMessage,
                    "code" to (if (e is HttpException) e.code else 500),
                )
            val errorJson = objectMapper.writeValueAsString(errorResponse)
            NanoHTTPD.newFixedLengthResponse(
                status,
                "application/json",
                errorJson,
            )
        }
}
