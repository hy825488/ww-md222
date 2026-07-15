package com.ck.wwmd222

import android.app.AndroidAppHelper
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ww_md222 — 偵測模式（Detect Mode）
 *
 * 不修改任何請求，只讀出帳號的區域資訊，並在遊戲畫面用 Toast 顯示約 5 秒。
 * hook org.json.JSONObject.put，攔 storeCurrency / orderToken / serverName。
 */
class MainHook : IXposedHookLoadPackage {
    companion object {
        const val TAG = "wwmd222"
        const val TARGET_PKG = "com.kurogame.wutheringwaves.global"

        // ★ 偵測模式開關：true=只偵測顯示不改; false=啟用 patch(改TW)
        const val DETECT_ONLY = true
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // 收集到的區域線索，湊齊一起顯示
    @Volatile private var lastCurrency: String? = null
    @Volatile private var lastServer: String? = null
    @Volatile private var lastRegionBlock: String? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PKG) return
        try {
            log("=== ww_md222 loaded (detect=${DETECT_ONLY}) ===")
            hookJsonPut(lpparam)
        } catch (t: Throwable) {
            log("install failed: ${t.message}")
        }
    }

    private fun hookJsonPut(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "org.json.JSONObject", lpparam.classLoader,
                "put", String::class.java, Any::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try { handlePut(param) } catch (_: Throwable) {}
                    }
                }
            )
            log("hook installed")
        } catch (t: Throwable) {
            log("findAndHook failed: ${t.message}")
        }
    }

    private fun handlePut(param: XC_MethodHook.MethodHookParam) {
        val args = param.args ?: return
        if (args.size < 2) return
        val key = args[0] as? String ?: return
        val value = args[1]

        when (key) {
            "storeCurrency" -> {
                if (value is String && value.isNotEmpty()) {
                    lastCurrency = value
                    log("storeCurrency = $value")
                    showRegionToast()
                }
            }
            "serverName" -> {
                if (value is String && value.isNotEmpty()) {
                    lastServer = value
                    log("serverName = $value")
                }
            }
            "orderToken" -> {
                if (value is String) {
                    // 讀出 orderToken 內部地區 block（唯讀）
                    val region = OrderTokenPatcher.readRegion(value)
                    if (region != null) {
                        lastRegionBlock = region
                        log("orderToken region@416 = $region")
                        log("region guess = ${OrderTokenPatcher.guessRegion(region)}")
                    }
                    // 偵測模式不改；非偵測模式才 patch
                    if (!DETECT_ONLY) {
                        val patched = OrderTokenPatcher.patch(value) { log(it) }
                        if (patched != null) args[1] = patched
                    }
                    showRegionToast()
                }
            }
        }
    }

    private fun currencyToRegion(cur: String?): String = when (cur?.uppercase()) {
        "TWD" -> "台灣 TW"
        "MYR" -> "馬來西亞 MY"
        "USD" -> "美區/未定 US"
        "HKD" -> "香港 HK"
        "SGD" -> "新加坡 SG"
        "JPY" -> "日本 JP"
        "THB" -> "泰國 TH"
        "PHP" -> "菲律賓 PH"
        "IDR" -> "印尼 ID"
        "KRW" -> "韓國 KR"
        "CNY" -> "中國 CN"
        "EUR" -> "歐元區 EU"
        null -> "?"
        else -> cur
    }

    private fun showRegionToast() {
        val cur = lastCurrency
        if (cur == null) return
        val region = currencyToRegion(cur)
        val server = lastServer?.let { "\n伺服器: $it" } ?: ""
        val msg = "【帳號區域偵測】\n幣別: $cur ($region)$server"

        mainHandler.post {
            try {
                val ctx: Context? = try { AndroidAppHelper.currentApplication() } catch (_: Throwable) { null }
                if (ctx == null) { log("no context for toast"); return@post }
                // Android Toast 最長約 3.5 秒，發兩次涵蓋約 5 秒
                showOnce(ctx, msg)
                mainHandler.postDelayed({ try { showOnce(ctx, msg) } catch (_: Throwable) {} }, 3200L)
            } catch (t: Throwable) {
                log("toast err: ${t.message}")
            }
        }
    }

    private fun showOnce(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }

    private fun log(msg: String) {
        try { XposedBridge.log("$TAG: $msg") } catch (_: Throwable) {}
    }
}
