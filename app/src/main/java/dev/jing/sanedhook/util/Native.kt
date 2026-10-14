package dev.jing.sanedhook.util

object Native {
    @JvmField
    val a: Native = this

    @Volatile
    var isLoaded: Boolean = false
        private set

    init {
        isLoaded = try {
            System.loadLibrary("shnative")
            true
        } catch (_: Throwable) {
            false
        }
    }

    external fun a(ctx: Any, pkg: String): Boolean
    external fun b(kind: Int, orderId: Int, token: String, lang: String, driverCipher: String): Array<String>
    external fun c(ctx: Any, pkg: String, s1: String, s2: String, v: Long): Int
    external fun d(): String
    external fun e(): String
    external fun f(): String
    external fun g(): String
    external fun h(): String
    external fun i(): String
    external fun j(): String
    external fun k(): Array<String>
    external fun l(): Array<String>
    external fun m(): Array<String>
    external fun n(): Array<String>
    external fun o(s: String): String
    external fun p(): Array<String>
    external fun q(): String
    external fun r(path: String): Boolean
    external fun s(versionCode: Long): Int
    external fun t(): Array<String>
    external fun u(): Array<String>
}
