package tools.wp2.sideloader.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private class FakeInstaller : Requester {
    val used = mutableMapOf<Int, ByteArray>()
    val calls = mutableListOf<Pair<Int, ByteArray>>()
    var beginStatus = Status.OK
    var stub = bytes(1) // stub 1 returns BAD_REQUEST for 0xFE

    override suspend fun call(c2: Int, payload: ByteArray, timeoutMs: Long): ByteArray {
        calls += c2 to payload
        return when (c2) {
            Cmd.INFO -> if (payload.u8(0) == STUB_SLOT) stub else bytes(0) + (used[payload.u8(0)] ?: ByteArray(0))
            Cmd.BEGIN -> bytes(beginStatus.code)
            Cmd.DATA -> buf { u8(0); le32(payload.le32(0) + payload.size - 4) }
            else -> bytes(0)
        }
    }
}

class WireProtocolTest {
    private val vectors: JSONObject by lazy {
        val text = javaClass.getResource("/vectors.json")?.readText()
            ?: error("Missing test resource /vectors.json")
        JSONObject(text)
    }
    private val pkgObj get() = vectors.getJSONObject("package")
    private val pkgBytes get() = hex(pkgObj.getString("hex"))
    private val headerBytes get() = hex(pkgObj.getString("info_reply_hex")).let { it.copyOfRange(1, it.size) }

    private fun namedHeader(c: Char) = headerBytes.also { it[8] = c.code.toByte() }

    @Test
    fun sealAndFrameMatchPythonVectors() {
        val s = vectors.getJSONObject("seal")
        val key = hex(s.getString("key"))
        assertContentEquals(hex(s.getString("cipher")), seal(key, hex(s.getString("plain"))))
        assertContentEquals(hex(s.getString("plain")), open(key, hex(s.getString("cipher"))))

        val f = vectors.getJSONObject("frame")
        val fKey = hex(f.getString("key"))
        val raw = hex(f.getString("hex"))
        assertContentEquals(raw, frame(f.getInt("c2"), hex(f.getString("payload")), fKey))

        val parser = FrameParser()
        assertTrue(parser.feed(raw.copyOf(5)).isEmpty())
        val got = parser.feed(raw.copyOfRange(5, raw.size)).single()
        assertEquals(SYSTEM, got.c1)
        assertEquals(f.getInt("c2"), got.c2)
        assertContentEquals(hex(f.getString("payload")), open(fKey, got.body))
    }

    @Test
    fun inspectPackageValidatesHeaderAndImageCrc() {
        val ins = assertNotNull(inspectPackage(pkgBytes))
        assertEquals(pkgObj.getString("name"), ins.header.name)
        assertEquals(pkgObj.getInt("kind"), ins.header.kind)
        assertEquals(pkgObj.getLong("version"), ins.header.version)
        assertTrue(ins.headerCrcOk, "header CRC should match")
        assertTrue(ins.imageCrcOk, "image CRC should match")
        assertTrue(ins.crcValid)

        val corrupted = pkgBytes.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0xFF).toByte() }
        val badIns = assertNotNull(inspectPackage(corrupted))
        assertTrue(badIns.headerCrcOk)
        assertFalse(badIns.imageCrcOk)
        assertFalse(badIns.crcValid)

        assertNull(inspectPackage(ByteArray(64)))
    }

    @Test
    fun installPayloadsMatchPythonAndSupportManualSlot() = runBlocking {
        val f = FakeInstaller()
        f.used += mapOf(0 to namedHeader('x'), 1 to namedHeader('y'), 2 to namedHeader('z'))
        val seen = mutableListOf<Double>()
        assertEquals(3, install(f, pkgBytes) { seen += it })
        val writes = f.calls.filter { it.first != Cmd.INFO }.map { it.second.hex() }
        val data = pkgObj.getJSONArray("data_hex").let { a -> List(a.length()) { a.getString(it) } }
        assertEquals(listOf(pkgObj.getString("begin_hex")) + data + pkgObj.getString("end_hex"), writes)
        assertEquals(1.0, seen.last())

        // Manual slot override
        val f2 = FakeInstaller()
        assertEquals(6, install(f2, pkgBytes, targetSlot = 6) {})
        assertEquals(6, f2.calls.first { it.first == Cmd.BEGIN }.second.u8(0))
    }

    @Test
    fun stubEightSupportsSixteenSlotsAndRemoveWorks() = runBlocking {
        val f = FakeInstaller().apply { stub = bytes(0, 8, 0, 0, 0, 4) }
        for (i in 0 until 8) f.used[i] = namedHeader('a' + i)
        assertEquals(8, install(f, pkgBytes) {})
        for (i in 8 until 16) f.used[i] = namedHeader('a' + i)
        assertEquals(16, assertFailsWith<InstallError.NoFreeSlot> { install(f, pkgBytes) {} }.slots)

        remove(f, 3)
        assertEquals(3, f.calls.last { it.first == Cmd.DELETE }.second.u8(0))
    }
}
