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

    // --- 1a. ATTRIBUTE UNLOCK via getter (legacy path) ---
    runCatching {
        ::productStateProtoFingerprint.hookMethod {
            after { param ->
                val result = param.result as? Map<String, *> ?: return@after
                XposedBridge.log("UnlockPremium: productStateProto.n() FIRED, size=${result.size}")
                UnlockPremiumPatch.overrideAttributes(result)
            }
        }
        XposedBridge.log("UnlockPremium: productStateProto getter hook INSTALLED")
    }.onFailure { XposedBridge.log("UnlockPremium: productStateProto getter hook FAILED: ${it.message}") }

    // --- 1b. ATTRIBUTE UNLOCK via parser q(byte[]) (9.1.90+: getter is never called) ---
    runCatching {
        val protoClass = classLoader.loadClass("com.spotify.remoteconfig.internal.ProductStateProto")
        val parseMethod = protoClass.getDeclaredMethod("q", ByteArray::class.java)
        XposedBridge.hookMethod(parseMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: HookParam) {
                val proto = param.result ?: return
                val valuesField = proto.javaClass.getDeclaredField("values_")
                valuesField.isAccessible = true
                val values = valuesField.get(proto) as? Map<String, *> ?: return
                XposedBridge.log("UnlockPremium: q(byte[]) FIRED, values size=${values.size}, keys=${values.keys.take(5)}")
                UnlockPremiumPatch.overrideAttributes(values)
            }
        })
        XposedBridge.log("UnlockPremium: productStateProto parser hook INSTALLED on $parseMethod")
    }.onFailure { XposedBridge.log("UnlockPremium: productStateProto parser hook FAILED: ${it.message}") }

    // --- 1c. ATTRIBUTE UNLOCK via k9k1.q(LinkedHashMap) (UCS builder path — the ACTUAL data flow in 9.1.90) ---
    runCatching {
        val k9k1Class = classLoader.loadClass("p.k9k1")
        val buildMethod = k9k1Class.getDeclaredMethod("q", java.util.LinkedHashMap::class.java)
        XposedBridge.hookMethod(buildMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: HookParam) {
                val czp0 = param.result ?: return
                val proto = XposedHelpers.getObjectField(czp0, "b") ?: return
                val valuesField = proto.javaClass.getDeclaredField("values_")
                valuesField.isAccessible = true
                val values = valuesField.get(proto) as? Map<String, *> ?: return
                XposedBridge.log("UnlockPremium: k9k1.q(LinkedHashMap) FIRED, values size=${values.size}, keys=${values.keys.take(10)}")
                UnlockPremiumPatch.overrideAttributes(values)
            }
        })
        XposedBridge.log("UnlockPremium: k9k1.q(LinkedHashMap) hook INSTALLED on $buildMethod")
    }.onFailure { XposedBridge.log("UnlockPremium: k9k1.q(LinkedHashMap) hook FAILED: ${it.message}") }

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
