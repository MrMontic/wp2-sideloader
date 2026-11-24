package tools.wp2.sideloader.wire

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel as Queue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object Uuid {
    const val SHELL_WRITE = "77D4FF01-2FE2-2334-0D35-9CCD078F529C"
    const val SHELL_NOTIFY = "77D4FF02-2FE2-2334-0D35-9CCD078F529C"
    const val CMD_WRITE = "0000FFF2-0000-1000-8000-00805F9B34FB"
    const val CMD_NOTIFY = "0000FFF1-0000-1000-8000-00805F9B34FB"
}

interface Link {
    suspend fun write(uuid: String, data: ByteArray)
    fun onNotify(handler: (String, ByteArray) -> Unit)
}

object Cmd {
    const val INFO = 0x90A0
    const val BEGIN = 0x90A1
    const val DATA = 0x90A2
    const val END = 0x90A3
    const val DELETE = 0x90A4
    const val MSG = 0x90A5
    const val PING = 0x90A7
    const val PUSH = 0x90A8
    const val EVENT = 0xB0A6
}

enum class Status {
    OK, BAD_REQUEST, IO, SEQUENCE, CRC, BUSY, INVALID, NO_APP, FULL, PAUSED;

    val code get() = ordinal
}

sealed class WireError(message: String) : Exception(message) {
    data class Timeout(val what: String) : WireError(what)
    data object NoApp : WireError("No app is currently running on the watch")
    data class Refused(val status: Int) : WireError("Watch refused request (code %02X)".format(status))
}

fun interface Requester {
    suspend fun call(c2: Int, payload: ByteArray, timeoutMs: Long): ByteArray
}

suspend fun Requester.call(c2: Int, payload: ByteArray) = call(c2, payload, 6000)

internal fun <T> Queue<T>.drain() {
    while (tryReceive().isSuccess) {
    }
}

internal suspend fun <T> Queue<T>.next(timeoutMs: Long): T? =
    if (timeoutMs <= 0) tryReceive().getOrNull() else withTimeoutOrNull(timeoutMs) { receive() }

class KSession private constructor(private val link: Link, val key: ByteArray, var pollMs: Long?) :
    Requester {
    private val frames = Queue<Pair<Int, ByteArray>>(Queue.UNLIMITED)
    private val events = Queue<ByteArray>(Queue.UNLIMITED)
    private val parser = FrameParser()

    companion object {
        suspend fun open(
            link: Link,
            secretHex: String? = null,
            pollMs: Long? = null,
            register: Boolean = true,
        ): Pair<KSession, String> {
            val shell = Queue<ByteArray>(Queue.UNLIMITED)
            val session = AtomicReference<KSession?>()
            link.onNotify { uuid, value ->
                when (uuid.uppercase()) {
                    Uuid.SHELL_NOTIFY -> shell.trySend(value)
                    Uuid.CMD_NOTIFY -> session.get()?.feed(value)
                }
            }
            var secret = secretHex
            var tries = 0
            while (secret == null) {
                tries++
                link.write(Uuid.SHELL_WRITE, "AT GETSECRET\r\n".toByteArray())
                var reply = ""
                while (listOf("OK", "ERROR", "FAIL").none { reply.contains(it) }) {
                    val d = shell.next(6000) ?: break
                    reply += String(d)
                }
                val t = reply.trim()
                if (t.startsWith("GETSECRET:") && t.endsWith(",OK")) {
                    secret = t.removePrefix("GETSECRET:").removeSuffix(",OK")
                } else if (tries == 3) {
                    throw WireError.Timeout("Failed to read pairing key (AT GETSECRET)")
                }
            }
            val key = channelKey(secret) ?: throw WireError.Refused(0xFF)
            val s = KSession(link, key, pollMs)
            session.set(s)
            if (register) s.ping()
            return s to secret
        }
    }

    @Synchronized
    internal fun feed(value: ByteArray) {
        for (f in parser.feed(value)) {
            if (f.c1 != SYSTEM) continue
            if (f.c2 == Cmd.EVENT) events.trySend(f.body) else frames.trySend(f.c2 to f.body)
        }
    }

    private suspend fun request(c2: Int, payload: ByteArray, timeoutMs: Long = 6000): ByteArray {
        frames.drain()
        link.write(Uuid.CMD_WRITE, frame(c2, payload, key))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val (got, body) = (if (left > 0) frames.next(left) else null)
                ?: throw WireError.Timeout("Command %04X timed out".format(c2))
            if (got == c2 or 0xA000) open(key, body)?.let { return it }
        }
    }

    suspend fun ping(): Pair<Int, Int> {
        val r = request(Cmd.PING, bytes(0))
        if (r.size != 3 || r.u8(0) != Status.OK.code) {
            throw WireError.Refused(r.firstOrNull()?.toInt()?.and(0xFF) ?: 0xFF)
        }
        return r.u8(1) to r.u8(2)
    }

    override suspend fun call(c2: Int, payload: ByteArray, timeoutMs: Long) =
        request(c2, payload, timeoutMs)
}
