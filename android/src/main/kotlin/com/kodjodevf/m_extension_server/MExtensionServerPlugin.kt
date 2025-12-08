package com.kodjodevf.m_extension_server

import android.app.Activity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import android.content.pm.PackageManager
import androidx.preference.PreferenceManager
import m_extension_server.controller.MExtensionServerController
import uy.kohesive.injekt.Injekt
import eu.kanade.tachiyomi.AppModule
import android.app.Application

var instance: MExtensionServerPlugin? = null

var pm: PackageManager? = null

var preferenceManager: PreferenceManager? = null

/** MExtensionServerPlugin */
class MExtensionServerPlugin :
    FlutterPlugin,
    MethodCallHandler,
    ActivityAware {
    // The MethodChannel that will the communication between Flutter and native Android
    //
    // This local reference serves to register the plugin with the Flutter Engine and unregister it
    // when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    var applicationContext: android.content.Context? = null
    private var serverController: MExtensionServerController? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = flutterPluginBinding.applicationContext
        preferenceManager = PreferenceManager(applicationContext!!)
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "m_extension_server")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(
        call: MethodCall,
        result: Result
    ) {
        when (call.method) {
            "startServer" -> {
                val port = call.argument<Int>("port") ?: 8080
                try {
                    if (serverController == null) {
                        serverController = MExtensionServerController()
                    }
                    serverController?.start(port)
                    result.success("Server started on port $port")
                } catch (e: Exception) {
                    result.error("START_ERROR", "Failed to start server: ${e.message}", null)
                }
            }
            "stopServer" -> {
                try {
                    serverController?.stop()
                    serverController = null
                    result.success("Server stopped")
                } catch (e: Exception) {
                    result.error("STOP_ERROR", "Failed to stop server: ${e.message}", null)
                }
            }
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        serverController?.stop()
        serverController = null
        channel.setMethodCallHandler(null)
    }

   
    // ActivityAware methods
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        applicationContext = activity?.applicationContext
        // Initialize Injekt with the Application context
        try {
            if (applicationContext != null) {
                Injekt.importModule(AppModule(applicationContext as Application))
            }
        } catch (e: Exception) {
            // Log the error but don't crash the app
            android.util.Log.e("MExtensionServer", "Failed to initialize Injekt: ${e.message}")
        }
        instance = this
        pm = activity?.packageManager
        onActivityCreated()
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    private fun onActivityCreated() {
       
    }
}
