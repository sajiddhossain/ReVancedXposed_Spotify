package io.github.chsbuffer.revancedxposed.spotify

import android.app.Application
import io.github.chsbuffer.revancedxposed.HookParam
import io.github.chsbuffer.revancedxposed.LoadPackageParam
import io.github.chsbuffer.revancedxposed.XC_MethodHook
import io.github.chsbuffer.revancedxposed.XposedBridge
import io.github.chsbuffer.revancedxposed.BaseHook
import io.github.chsbuffer.revancedxposed.injectHostClassLoaderToSelf
import io.github.chsbuffer.revancedxposed.spotify.misc.privacy.SanitizeSharingLinks
import io.github.chsbuffer.revancedxposed.spotify.misc.widgets.FixThirdPartyLaunchersWidgets

@Suppress("UNCHECKED_CAST")
class SpotifyHook(
    app: Application,
    lpparam: LoadPackageParam,
) : BaseHook(app, lpparam) {
    override val hooks = buildList {
        add(::Extension)
        add(::SanitizeSharingLinks)
        add(::FixThirdPartyLaunchersWidgets)
    }.toTypedArray()

    // ══════════════════════════════════════════════════════
    // EXTENSION LOADER
    // ══════════════════════════════════════════════════════
    fun Extension() {
        injectHostClassLoaderToSelf(this::class.java.classLoader!!, classLoader)
    }

    // ══════════════════════════════════════════════════════
    // NHB → AD BLOCKER (blocks ad-serving endpoints)
    // ══════════════════════════════════════════════════════
    private val NHB_BLOCKED_AD_SEGMENTS = listOf(
        "/ad-logic/",
        "/ads/v2/",
        "/v1/ads/",
        "/gabo-receiver-service/",
    )

    fun NHB() {
        runCatching {
            val httpConnectionImpl =
                classLoader.loadClass("com.spotify.core.http.NativeHttpConnection")

            val httpRequest =
                classLoader.loadClass("com.spotify.core.http.HttpRequest")

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
                            param.result = null
                        }
                    }
                }
            )

            XposedBridge.log("NHB: ad-blocker hook INSTALLED")
        }.onFailure {
            XposedBridge.log("NHB error -> ${it.message}")
        }
    }
}
