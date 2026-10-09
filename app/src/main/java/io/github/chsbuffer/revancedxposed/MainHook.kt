package io.github.chsbuffer.revancedxposed

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class MainHook : XposedModule() {
    companion object {
        const val TAG = "ReVancedXposed"

        lateinit var module: MainHook
            private set

        fun log(tag: String, msg: String, tr: Throwable? = null) {
            if (::module.isInitialized) {
                if (tr != null) {
                    module.log(Log.INFO, tag, msg, tr)
                } else {
                    module.log(Log.INFO, tag, msg)
                }
            } else {
                if (tr != null) {
                    Log.e(tag, msg, tr)
                } else {
                    Log.i(tag, msg)
                }
            }
        }
    }

    private val targetPackages = setOf("com.spotify.music")
    private var initialized = false

    fun shouldHook(packageName: String): Boolean {
        return targetPackages.contains(packageName)
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        log(TAG, "onModuleLoaded: ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        module = this
        if (!param.isFirstPackage) return
        if (!shouldHook(param.packageName)) return

        log(TAG, "onPackageReady: ${param.packageName}")

        val lpparam = PackageParam(
            packageName = param.packageName,
            classLoader = param.classLoader,
            appInfo = param.applicationInfo,
            isFirstPackage = param.isFirstPackage
        )

        val attachMethod = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hook(attachMethod).intercept { chain ->
            val res = chain.proceed()
            val app = chain.thisObject as? Application
            if (app != null) {
                onApplicationAttached(app, lpparam)
            }
            res
        }
    }

    private val NHB_BLOCKED_SEGMENTS = listOf(
        "/ad-logic/",
        "/ads/v2/",
        "/v1/ads/",
        "/gabo-receiver-service/",
    )

    private fun onApplicationAttached(app: Application, lpparam: PackageParam) {
        if (initialized) return
        initialized = true

        log(TAG, "NHB ad-blocker only mode — no DexKit, no classloader injection")

        // --- NHB AD BLOCKER (direct, no BaseHook/DexKit) ---
        try {
            val cl = lpparam.classLoader
            val httpConn = cl.loadClass("com.spotify.core.http.NativeHttpConnection")
            val httpReq = cl.loadClass("com.spotify.core.http.HttpRequest")
            val urlField = httpReq.getDeclaredField("url")
            urlField.isAccessible = true

            XposedBridge.hookAllMethods(httpConn, "send", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    val req = param.args[0]
                    val url = urlField.get(req) as? String ?: return
                    if (NHB_BLOCKED_SEGMENTS.any { url.contains(it, true) }) {
                        param.result = null
                    }
                }
            })
            log(TAG, "NHB: ad-blocker hook INSTALLED")
        } catch (e: Exception) {
            log(TAG, "NHB failed: ${e.message}", e)
        }
    }

}

const val PREF_FILE = "spotify_prefs"
const val PREF_ENABLE_PREMIUM = "enable_premium"
const val PREF_ENABLE_ADBLOCK = "enable_adblock"
const val PREF_ENABLE_MONET = "enable_monet"
const val PREF_ENABLE_ROUND_UI = "enable_round_ui"
