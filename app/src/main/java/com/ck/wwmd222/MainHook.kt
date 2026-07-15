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
 * ww_md222 — 偵測模式 v2
 *
 * 改用 hook android.util.Base64.encode(byte[], int)，而非 JSONObject.put。
 * 原因：鳴潮 native 反作弊會自檢常見 Java 方法(如 JSONObject.put)是否被 hook，
 * 一旦發現就 native abort(閃退)。Base64.encode 較冷門，且 data 欄位最終
 * 一定經過它，攔這裡同樣能拿到明文 JSON。
 *
 * 偵測模式：只讀 storeCurrency / orderToken 區域，Toast 顯示，不改內容。
 */
class MainHook : IXposedHookLoadPackage {
    companion object {
        const val TAG = "wwmd222"
        const val TARGET_PKG = "com.kurogame.wutheringwaves.global"
        const val DETECT_ONLY = true
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var lastCurrency: String? = null
    @Volatile private var lastRegionBlock: String? = null
    @Volatile private var lastToastAt: Long = 0

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PKG) return
        try {
            log("=== ww_md222 loaded v2 (detect=$DETECT_ONLY) ===")
            hookBase64(lpparam)
        } catch (t: Throwable) {
            log("install failed: ${t.message}")
        }
    }

    private fun hookBase64(lpparam: XC_LoadPackage.LoadPackageParam) {
        // android.util.Base64.encode(byte[] input, int flags) : byte[]
        try {
            XposedHelpers.findAndHookMethod(
                "android.util.Base64", lpparam.classLoader,
                "encode", ByteArray::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try { val a = param.args; if (a != null && a.isNotEmpty()) inspect(a[0] as? ByteArray) } catch (_: Throwable) {}
                    }
                }
            )
            log("hook Base64.encode(byte[],int) installed")
        } catch (t: Throwable) {
            log("hook encode(byte[],int) failed: ${t.message}")
        }

        // 備援：encodeToString(byte[], int)
        try {
            XposedHelpers.findAndHookMethod(
                "android.util.Base64", lpparam.classLoader,
                "encodeToString", ByteArray::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try { val a = param.args; if (a != null && a.isNotEmpty()) inspect(a[0] as? ByteArray) } catch (_: Throwable) {}
                    }
                }
            )
            log("hook encodeToString installed")
        } catch (t: Throwable) {
            log("hook encodeToString failed: ${t.message}")
        }
    }

    private fun inspect(data: ByteArray?) {
        if (data == null || data.size < 50) return
        // 快速過濾：只處理含 orderToken 的 payload
        val text = try { String(data, Charsets.UTF_8) } catch (_: Throwable) { return }
        if (!text.contains("orderToken")) return

        // 讀 storeCurrency
        val cur = Regex("\"storeCurrency\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
        // 讀 orderToken 區域
        val token = Regex("\"orderToken\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
        val region = OrderTokenPatcher.readRegion(token)

        if (cur != null) { lastCurrency = cur; log("storeCurrency = $cur") }
        if (region != null) {
            lastRegionBlock = region
            log("region@416 = $region (${OrderTokenPatcher.guessRegion(region)})")
        }
        showRegionToast()
    }

    private fun currencyToRegion(cur: String?): String = when (cur?.uppercase()) {
        "TWD" -> "台灣 TW"; "MYR" -> "馬來西亞 MY"; "USD" -> "美區/未定 US"
        "HKD" -> "香港 HK"; "SGD" -> "新加坡 SG"; "JPY" -> "日本 JP"
        "THB" -> "泰國 TH"; "PHP" -> "菲律賓 PH"; "IDR" -> "印尼 ID"
        "KRW" -> "韓國 KR"; "CNY" -> "中國 CN"; "EUR" -> "歐元區 EU"
        null -> "?"; else -> cur
    }

    private fun showRegionToast() {
        val cur = lastCurrency ?: return
        // 防洪：2 秒內不重複跳
        val now = System.currentTimeMillis()
        if (now - lastToastAt < 2000) return
        lastToastAt = now

        val msg = "【帳號區域偵測】\n幣別: $cur (${currencyToRegion(cur)})"
        mainHandler.post {
            try {
                val ctx: Context = try { AndroidAppHelper.currentApplication() } catch (_: Throwable) { null } ?: return@post
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                mainHandler.postDelayed({
                    try {
                        val c2: Context = AndroidAppHelper.currentApplication() ?: return@postDelayed
                        Toast.makeText(c2, msg, Toast.LENGTH_LONG).show()
                    } catch (_: Throwable) {}
                }, 3200L)
            } catch (t: Throwable) { log("toast err: ${t.message}") }
        }
    }

    private fun log(msg: String) {
        try { XposedBridge.log("$TAG: $msg") } catch (_: Throwable) {}
    }
}
