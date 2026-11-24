package tools.wp2.sideloader.wire

const val STUB_SLOT = 0xFE

data class Stub(val version: Long, val abiMinor: Int?)

data class LoaderState(
    val stub: Stub,
    val loader: PackageHeader?,
    val apps: Map<Int, PackageHeader>,
) {
    val totalSlots: Int get() = appSlots(stub.version)
    val freeSlots: Int get() = totalSlots - apps.size
}

fun parseStubReply(r: ByteArray): Stub? {
    if (r.isNotEmpty() && r.u8(0) == Status.BAD_REQUEST.code) return Stub(1, null)
    if (r.size != 6 || r.u8(0) != Status.OK.code) return null
    return Stub(r.le32(1), r.u8(5))
}

suspend fun readLoader(s: Requester, probeMs: Long = 2500): LoaderState? {
    val reply = try {
        s.call(Cmd.INFO, bytes(STUB_SLOT), probeMs)
    } catch (_: WireError.Timeout) {
        return null
    }
    val stub = parseStubReply(reply)
        ?: throw WireError.Refused(if (reply.isEmpty()) 0xFF else reply.u8(0))

    suspend fun read(slot: Int): PackageHeader? {
        val r = s.call(Cmd.INFO, bytes(slot), 4000)
        return if (r.isNotEmpty() && r.u8(0) == Status.OK.code) {
            parseHeader(r.copyOfRange(1, r.size))
        } else {
            null
        }
    }

    val apps = sortedMapOf<Int, PackageHeader>()
    for (slot in 0 until appSlots(stub.version)) {
        read(slot)?.let { apps[slot] = it }
    }
    return LoaderState(stub, read(LOADER_SLOT), apps)
}
