/*
 * Patched by: _sajiddz  (Discord: 6aq4)
 * Fork of pizzaschleppa/ReVancedXposed_Spotify
 * Updated for Spotify 9.1.90+
 */
package io.github.chsbuffer.revancedxposed.spotify.misc

import app.revanced.extension.shared.Logger
import app.revanced.extension.spotify.misc.UnlockPremiumPatch
import io.github.chsbuffer.revancedxposed.HookParam
import io.github.chsbuffer.revancedxposed.XC_MethodHook
import io.github.chsbuffer.revancedxposed.XposedBridge
import io.github.chsbuffer.revancedxposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.callMethod
import io.github.chsbuffer.revancedxposed.findField
import io.github.chsbuffer.revancedxposed.findFirstFieldByExactType
import io.github.chsbuffer.revancedxposed.spotify.SpotifyHook
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Constructor
import java.lang.reflect.Field

@Suppress("UNCHECKED_CAST")
fun SpotifyHook.UnlockPremium() {

    XposedBridge.log("UnlockPremium: starting hook setup")

    // --- 1. ATTRIBUTE UNLOCK (CORE PREMIUM) ---
    runCatching {
        ::productStateProtoFingerprint.hookMethod {
            after { param ->
                val result = param.result as? Map<String, *> ?: run {
                    XposedBridge.log("UnlockPremium: productStateProto fired but result is null or not Map (${param.result?.javaClass})")
                    return@after
                }
                XposedBridge.log("UnlockPremium: productStateProto FIRED, map keys=${result.keys.take(5)}, size=${result.size}")
                UnlockPremiumPatch.overrideAttributes(result)
                XposedBridge.log("UnlockPremium: overrideAttributes done")
            }
        }
        XposedBridge.log("UnlockPremium: productStateProto hook INSTALLED OK")
    }.onFailure { XposedBridge.log("UnlockPremium: productStateProto hook FAILED: ${it.message}") }

    // --- 2. POPULAR TRACKS (ARTIST PAGE) ---
    runCatching {
        ::buildQueryParametersFingerprint.hookMethod {
            after { param ->
                val result = param.result ?: return@after
                val fieldName = "checkDeviceCapability"
                if (result.toString().contains("$fieldName=")) {
                    param.result = XposedBridge.invokeOriginalMethod(
                        param.method, param.thisObject, arrayOf(param.args[0], true)
                    )
                }
            }
        }
    }.onFailure { XposedBridge.log("UnlockPremium: buildQueryParameters hook FAILED: ${it.message}") }

    // --- 3. GOOGLE ASSISTANT (FIX URIs) ---
    runCatching {
        ::contextFromJsonFingerprint.hookMethod {
            fun safeRemoveStation(field: Field?, obj: Any?) {
                if (field == null || obj == null) return
                runCatching {
                    val value = field.get(obj) as? String ?: return
                    field.set(obj, UnlockPremiumPatch.removeStationString(value))
                }
            }

            after { param ->
                val result = param.result ?: return@after
                val clazz = result.javaClass
                safeRemoveStation(clazz.findField("uri"), result)
                safeRemoveStation(clazz.findField("url"), result)
            }
        }
    }.onFailure { XposedBridge.log("UnlockPremium: contextFromJson hook FAILED: ${it.message}") }

    // --- 4. ANTI-SHUFFLE (GOOGLE ASSISTANT) ---
    runCatching {
        XposedHelpers.findAndHookMethod(
            $$"com.spotify.player.model.command.options.AutoValue_PlayerOptionOverrides$Builder",
            classLoader,
            "build",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    param.thisObject?.callMethod("shufflingContext", false)
                }
            })
    }.onFailure { XposedBridge.log("UnlockPremium: PlayerOptionOverrides hook FAILED: ${it.message}") }

    // --- 5. CONTEXT MENU CLEANUP (REMOVE ADS) ---
    runCatching {
        val contextMenuViewModelClazz = ::contextMenuViewModelClass.clazz
        XposedBridge.hookAllConstructors(contextMenuViewModelClazz, object : XC_MethodHook() {
            val isPremiumUpsell = runCatching { ::isPremiumUpsellField.field }.getOrNull()

            override fun beforeHookedMethod(param: HookParam) {
                if (isPremiumUpsell == null) return
                val parameterTypes = (param.method as Constructor<*>).parameterTypes
                for (i in param.args.indices) {
                    if (parameterTypes[i].name != "java.util.List") continue
                    val original = param.args[i] as? List<*> ?: continue

                    // Filter out items that lead to Premium ads.
                    val filtered = original.filter { item ->
                        val vm = item?.callMethod("getViewModel")
                        vm?.let { isPremiumUpsell.get(it) as? Boolean } != true
                    }
                    param.args[i] = filtered
                }
            }
        })
    }.onFailure { XposedBridge.log("UnlockPremium: ContextMenu hook FAILED: ${it.message}") }

    // --- 6. REMOVE AD SECTIONS (HOME & BROWSE) ---
    runCatching {
        ::homeStructureGetSectionsFingerprint.hookMethod {
            after { param ->
                val sections = param.result as? MutableList<*> ?: return@after
                runCatching {
                    sections.javaClass.findFirstFieldByExactType(Boolean::class.java).set(sections, true)
                    UnlockPremiumPatch.removeHomeSections(sections)
                }
            }
        }
    }.onFailure { XposedBridge.log("UnlockPremium: homeStructure hook FAILED: ${it.message}") }

    runCatching {
        ::browseStructureGetSectionsFingerprint.hookMethod {
            after { param ->
                val sections = param.result as? MutableList<*> ?: return@after
                runCatching {
                    sections.javaClass.findFirstFieldByExactType(Boolean::class.java).set(sections, true)
                    UnlockPremiumPatch.removeBrowseSections(sections)
                }
            }
        }
    }.onFailure { XposedBridge.log("UnlockPremium: browseStructure hook FAILED: ${it.message}") }

    // --- 7. BLOCK AD POPUPS (PENDRAGON) ---
    runCatching {
        val replaceWithRxError = object : XC_MethodHook() {
            val justMethod = DexMethod("Lio/reactivex/rxjava3/core/Single;->just(Ljava/lang/Object;)Lio/reactivex/rxjava3/core/Single;").toMethod()
            val onErrorField = DexField("Lio/reactivex/rxjava3/internal/operators/single/SingleOnErrorReturn;->b:Lio/reactivex/rxjava3/functions/Function;").toField()

            override fun afterHookedMethod(param: HookParam) {
                val res = param.result ?: return
                if (!res.javaClass.name.endsWith("SingleOnErrorReturn")) return
                runCatching {
                    val errorFunc = onErrorField.get(res)
                    val applyMethod = errorFunc.javaClass.getMethod("apply", java.lang.Object::class.java)
                    val fallbackValue = applyMethod.invoke(errorFunc, Exception("Pendragon block"))
                    param.result = justMethod.invoke(null, fallbackValue)
                }
            }
        }

        ::pendragonJsonFetchMessageRequestFingerprint.hookMethod(replaceWithRxError)
        ::pendragonJsonFetchMessageListRequestFingerprint.hookMethod(replaceWithRxError)
    }.onFailure { XposedBridge.log("UnlockPremium: pendragon hook FAILED: ${it.message}") }
}
