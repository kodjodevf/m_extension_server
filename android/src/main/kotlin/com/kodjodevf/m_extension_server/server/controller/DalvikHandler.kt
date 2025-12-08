package com.kodjodevf.m_extension_server.server.controller

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
import java.io.File
import java.net.URI

class DalvikHandler {
    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        var tempApkFile: File? = null
        try {

            // Parse JSON body first to get extension data
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            val json = body["postData"] ?: throw IllegalArgumentException("No JSON body")

            // Deserialize DataBody
            val dataBody = objectMapper.readValue(json, DataBody::class.java)

            // Load extension
            val loadedSource = MExtensionServerLoader.loadSourceFromBase64(dataBody.data)
            tempApkFile = loadedSource.tempApkFile;
            // Get domain from source
            val domain =
                loadedSource.source?.let { source ->
                    try {
                        val baseUrl = source.javaClass.getMethod("getBaseUrl").invoke(source) as String
                        URI(baseUrl).host
                    } catch (e: Exception) {
                        null
                    }
                } ?: "localhost"

           // Intercept Cookie header and save to global cookie jar
           val cookies =
               (session.headers["cookie"] ?: session.headers["Cookie"])
                   ?.let { cookieHeader ->
                       cookieHeader.split(";").map { cookieStr ->
                           val trimmed = cookieStr.trim()
                           val parts = trimmed.split("=", limit = 2)
                           val name = parts[0].trim()
                           val value = parts[1].trim()
                           Cookie
                               .Builder()
                               .name(name)
                               .value(value)
                               .domain(domain.removePrefix("."))
                               .path("/")
                               .build()
                       }
                   }?.toList()
           val network =
               loadedSource.source?.let { source ->
                   when (source) {
                       is HttpSource -> source.network
                       is AnimeHttpSource -> source.network
                       else -> null
                   }
               }
           if (cookies != null) {
               network?.cookieJar?.addAll(
                   HttpUrl
                       .Builder()
                       .scheme("http")
                       .host(domain.removePrefix("."))
                       .build(),
                   cookies,
               )
           }
           val ua = (session.headers["user-agent"] ?: session.headers["User-Agent"])
           if (ua != null) {
               network?.setUA(ua)
           }

            // Invoke method
            val result = MihonInvoker.invokeMethod(loadedSource.source, dataBody)

            // Serialize response
            val responseJson = objectMapper.writeValueAsString(result)

           return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                responseJson,
            )
        } catch (e: Exception) {
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
                    else -> NanoHTTPD.Response.Status.INTERNAL_ERROR
                }
            val errorResponse =
                mapOf(
                    "error" to (e.message ?: "Unknown error"),
                    "code" to (if (e is HttpException) e.code else 500),
                )
            val errorJson = objectMapper.writeValueAsString(errorResponse)
           return NanoHTTPD.newFixedLengthResponse(
                status,
                "application/json",
                errorJson,
            )
        }finally {
            // Clean up APK file
            if (tempApkFile?.exists() ?: false ) {
                tempApkFile.delete()
            }
        }
    }


}
