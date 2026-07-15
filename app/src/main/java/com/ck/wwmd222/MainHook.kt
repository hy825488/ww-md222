package com.ck.wwmd222

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * ww_md222 安卓版 — 對應 iOS 的 Tweak.xm
 *
 * 只針對鳴潮國際版。hook org.json.JSONObject.put(String, Object)，
 * 但用極保守的前置過濾：絕大多數呼叫在第一個 if 就放行，
 * 只有 key 完全等於 storeArea / orderToken 時才進一步處理。
 * 全程 try-catch 包死，任何異常都只是「不改」，絕不讓 App 崩潰。
 */
class MainHook : IXposedHookLoadPackage {
    companion object {
        const val TAG = "wwmd222"
        const val TARGET_PKG = "com.kurogame.wutheringwaves.global"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PKG) return

        try {
            log("=== ww_md222 loaded ===")
            hookJsonPut(lpparam)
        } catch (t: Throwable) {
            // 連 hook 安裝都失敗也不能讓 App 掛
            log("install hook failed: ${t.message}")
        }
    }

    private fun hookJsonPut(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "org.json.JSONObject",
                lpparam.classLoader,
                "put",
                String::class.java,
                Any::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 這裡是熱路徑，會被呼叫非常多次，必須極快返回
                        try {
                            val args = param.args ?: return
                            if (args.size < 2) return

                            val key = args[0]
                            // 快速過濾：只認這兩個 key，其餘立刻放行
                            if (key !== "storeArea" && key != "storeArea" &&
                                key !== "orderToken" && key != "orderToken") {
                                return
                            }

                            val value = args[1]
                            if (value !is String) return

                            when (key) {
                                "storeArea" -> {
                                    if (value.isNotEmpty()) {
                                        log("storeArea cleared")
                                        args[1] = ""
                                    }
                                }
                                "orderToken" -> {
                                    val patched = OrderTokenPatcher.patch(value) { log(it) }
                                    if (patched != null) {
                                        args[1] = patched
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            // 出任何錯都吞掉，維持原值，保證不崩
                            try { log("hook err: ${t.message}") } catch (_: Throwable) {}
                        }
                    }
                }
            )
            log("hook installed")
        } catch (t: Throwable) {
            log("findAndHook failed: ${t.message}")
        }
    }

    private fun log(msg: String) {
        try { XposedBridge.log("$TAG: $msg") } catch (_: Throwable) {}
    }
}
