package dev.saned.assistant

import android.content.Context
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {

    companion object {
        const val TAG = "SanedAssistant"
        const val TARGET_PACKAGE = "net.jahez.fleets"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        XposedBridge.log("[$TAG] تم حقن موديول Saned Assistant داخل جاهز بنجاح!")

        // التقاط سياق التطبيق وتحديث الموقع
        XposedHelpers.findAndHookMethod(
            "android.app.Application",
            lpparam.classLoader,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as Context
                    LocationProvider.updateLocation(app)
                    XposedBridge.log("[$TAG] تم تحديث موقع السائق الأولي.")
                }
            }
        )

        // اعتراض استلام الطلبات من السيرفر (OkHttp / Retrofit / Response)
        try {
            XposedHelpers.findAndHookMethod(
                "okhttp3.Response",
                lpparam.classLoader,
                "code",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val code = param.result as? Int ?: return
                        if (code in 400..499) {
                            Log.w(TAG, "رد خادم جاهز برمز خطأ: $code")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            // okhttp قد يكون بأسماء مختلفة بحسب الإصدار
        }
    }
}
