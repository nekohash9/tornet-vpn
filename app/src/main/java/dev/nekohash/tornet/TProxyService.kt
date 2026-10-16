@file:Suppress("FunctionName")

package dev.nekohash.tornet

/**
 * JNI-обёртка над hev-socks5-tunnel (tun2socks): читает пакеты из tun-интерфейса
 * и отправляет TCP-соединения в SOCKS5-порт Tor.
 *
 * Имена методов и класса заданы в hev-jni.c, их нельзя переименовывать.
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray?
}
