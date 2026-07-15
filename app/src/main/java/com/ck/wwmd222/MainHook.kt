package com.ck.wwmd222

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ww_md222 安卓版 — 對應 iOS 的 Tweak.xm
 *
 * iOS 是 hook NSMutableDictionary setObject:forKey:，在支付請求序列化前
 * 改 storeArea 和 orderToken。安卓最忠實的對應是 hook org.json.JSONObject.put()，
 * 因為鳴潮安卓的 data 欄位就是一包 JSON（已用真實封包確認）。
 */
class MainHook : IXposedHookLoadPackage {
    companion object {
        const val TAG = "wwmd222"
        const val TARGET_PKG = "com.kurogame.wutheringwaves.global"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PKG) return

        log("=== ww_md222 loaded in ${lpparam.packageName} ===")
        hookJsonPut(lpparam)
    }

    private fun hookJsonPut(lpparam: XC_LoadPackage.LoadPackageParam) {
        // org.json.JSONObject.put(String, Object) — 對應 iOS 的 setObject:forKey:
        // 有多個多載，逐一 hook value 為 Object 的那個
        try {
            XposedHelpers.findAndHookMethod(
                "org.json.JSONObject",
                lpparam.classLoader,
                "put",
                String::class.java,
                Any::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        handlePut(param)
                    }
                }
            )
            log("Hook JSONObject.put(String, Object) success")
        } catch (e: Throwable) {
            log("Hook put(String,Object) failed: ${e.message}")
        }
    }

    private fun handlePut(param: XC_MethodHook.MethodHookParam) {
        try {
            val args = param.args ?: return
            if (args.size < 2) return
            val key = args[0] as? String ?: return
            val value = args[1]

            // --- storeArea: 設為空字串（對應 iOS）---
            if (key == "storeArea") {
                if (value is String && value.isNotEmpty()) {
                    log("storeArea: \"$value\" -> \"\"")
                    args[1] = ""
                }
                return
            }

            // --- orderToken: 替換地區 block ---
            if (key == "orderToken") {
                if (value is String) {
                    val patched = OrderTokenPatcher.patch(value) { log(it) }
                    if (patched != null) {
                        args[1] = patched
                    }
                }
                return
            }
        } catch (e: Throwable) {
            log("handlePut error: ${e.message}")
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("$TAG: $msg")
    }
}
