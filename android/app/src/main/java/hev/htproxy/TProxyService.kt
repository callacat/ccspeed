package hev.htproxy

/**
 * hev-socks5-tunnel 2.20.0 官方 JNI 的 Java 侧类。
 *
 * 类名/包名必须与 .so 里 RegisterNatives 用的字符串完全一致（hev/htproxy/TProxyService），
 * 否则 JNI_OnLoad 的 FindClass 返回 null、调用时 UnsatisfiedLinkError。
 * 这里的声明直接照抄官方 classes.jar 的 TProxyService。
 */
class TProxyService {
    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
        @JvmStatic external fun TProxyStopService(): Boolean
        @JvmStatic external fun TProxyIsRunning(): Boolean
        @JvmStatic external fun TProxyGetStats(): LongArray
    }
}