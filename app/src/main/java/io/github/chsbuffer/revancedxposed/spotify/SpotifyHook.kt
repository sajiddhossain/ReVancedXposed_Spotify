package io.github.chsbuffer.revancedxposed.spotify

import android.app.Application
import io.github.chsbuffer.revancedxposed.HookParam
import io.github.chsbuffer.revancedxposed.LoadPackageParam
import io.github.chsbuffer.revancedxposed.XC_MethodHook
import io.github.chsbuffer.revancedxposed.XposedBridge
import io.github.chsbuffer.revancedxposed.BaseHook
import io.github.chsbuffer.revancedxposed.injectHostClassLoaderToSelf
import io.github.chsbuffer.revancedxposed.spotify.misc.UnlockPremium
import io.github.chsbuffer.revancedxposed.spotify.misc.logout.LogOutPatch
import io.github.chsbuffer.revancedxposed.spotify.misc.privacy.SanitizeSharingLinks
import io.github.chsbuffer.revancedxposed.spotify.misc.widgets.FixThirdPartyLaunchersWidgets

@Suppress("UNCHECKED_CAST")
class SpotifyHook(
    app: Application,
    lpparam: LoadPackageParam,
    enablePremium: Boolean = true
) : BaseHook(app, lpparam) {
    override val hooks = buildList {
        add(::Extension)
        add(::SanitizeSharingLinks)
        if (enablePremium) add(::UnlockPremium)
        add(::LogOutPatch)
        add(::FixThirdPartyLaunchersWidgets)
        add(::NHB)
    }.toTypedArray()

    // ══════════════════════════════════════════════════════
    // EXTENSION LOADER
    // ══════════════════════════════════════════════════════
    fun Extension() {
        injectHostClassLoaderToSelf(this::class.java.classLoader!!, classLoader)
    }

    // ══════════════════════════════════════════════════════
    // NHB → NATIVE HTTP BLOCK (targeted)
    // ══════════════════════════════════════════════════════
    private val NHB_BLOCKED_AD_SEGMENTS = listOf(
        "/ad-logic/",
        "/ads/v2/",
        "/v1/ads/",
        "/gabo-receiver-service/",
    )

    private val NHB_BLOCKED_DETECTION_SEGMENTS = listOf(
        "melody/v1/check",
        "reachability/check",
        "dual-sync",
        "social-connect",
        "/v1/pigeon/",
        "/eventdelivery/",
        "/event-service/",
        "pitoken/",
        "track-error/v1/errors",
    )

    fun NHB() {
        runCatching {

            val cl = classLoader

            val httpConnectionImpl =
                cl.loadClass("com.spotify.core.http.NativeHttpConnection")

            val httpRequest =
                cl.loadClass("com.spotify.core.http.HttpRequest")

            val urlField = httpRequest.getDeclaredField("url")
            urlField.isAccessible = true

            XposedBridge.hookAllMethods(
                httpConnectionImpl,
                "send",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: HookParam) {
                        val req = param.args[0]
                        val url = urlField.get(req) as? String ?: return

                        if (NHB_BLOCKED_AD_SEGMENTS.any { url.contains(it, true) }) {
                            XposedBridge.log("NHB AD-BLOCK: $url")
                            param.result = null
                            return
                        }

                        if (NHB_BLOCKED_DETECTION_SEGMENTS.any { url.contains(it, true) }) {
                            XposedBridge.log("NHB DETECT-BLOCK: $url")
                            param.result = null
                            return
                        }

                        // Log spclient requests to discover new detection endpoints
                        if (url.contains("spclient", true)) {
                            XposedBridge.log("NHB PASS spclient: $url")
                        }
                    }
                }
            )

            // Hook onHeaders to see server response codes
            XposedBridge.hookAllMethods(httpConnectionImpl, "onHeaders", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    val resp = param.args[0] ?: return
                    val status = runCatching {
                        val f = resp.javaClass.getDeclaredField("status")
                        f.isAccessible = true
                        f.getInt(resp)
                    }.getOrNull()
                    val respUrl = runCatching {
                        val f = resp.javaClass.getDeclaredField("url")
                        f.isAccessible = true
                        f.get(resp) as? String
                    }.getOrNull() ?: ""
                    if (respUrl.contains("playplay") || respUrl.contains("storage-resolve") || (status != null && status >= 400)) {
                        XposedBridge.log("NHB RESP: status=$status url=$respUrl")
                    }
                }
            })

            // Hook onError to see native errors
            XposedBridge.hookAllMethods(httpConnectionImpl, "onError", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    val errorCode = param.args[0]
                    XposedBridge.log("NHB onError: code=$errorCode")
                }
            })

            XposedBridge.log("NHB: NativeHttpConnection hook INSTALLED (send+onHeaders+onError)")

        }.onFailure {
            XposedBridge.log("NHB error -> ${it.message}")
        }

        // --- Play Integrity neutralization ---
        runCatching {
            val oii1Class = classLoader.loadClass("p.oii1")
            XposedBridge.hookAllMethods(oii1Class, "a", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    XposedBridge.log("NHB: Play Integrity warmup BLOCKED (oii1.a)")
                    param.result = null
                }
            })
            XposedBridge.log("NHB: Play Integrity warmup hook INSTALLED (oii1)")
        }.onFailure {
            XposedBridge.log("NHB: Play Integrity warmup hook FAILED: ${it.message}")
        }

        runCatching {
            val ukj1Class = classLoader.loadClass("p.ukj1")
            XposedBridge.hookAllMethods(ukj1Class, "g", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    XposedBridge.log("NHB: Play Integrity token acquire BLOCKED (ukj1.g)")
                    param.result = null
                }
            })
            XposedBridge.hookAllMethods(ukj1Class, "i", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    XposedBridge.log("NHB: Play Integrity token init BLOCKED (ukj1.i)")
                    param.result = null
                }
            })
            XposedBridge.log("NHB: Play Integrity token hook INSTALLED (ukj1)")
        }.onFailure {
            XposedBridge.log("NHB: Play Integrity token hook FAILED: ${it.message}")
        }
    }
}
