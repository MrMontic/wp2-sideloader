package tools.wp2.sideloader.wire

const val LOADER_SLOT = 0xFF
private const val HEADER_SIZE = 64
private const val HEADER_CRC_SPAN = 0x34
private const val DATA_MAX = 128

fun appSlots(stub: Long): Int = if (stub >= 8) 16 else 8

suspend fun stubVersion(s: Requester): Long =
    parseStubReply(s.call(Cmd.INFO, bytes(STUB_SLOT)))?.version ?: 1

data class PackageHeader(
    val kind: Int,
    val name: String,
    val version: Long,
    val crc: Long = 0,
) {
    val isLoader: Boolean get() = kind == 1
    val isApp: Boolean get() = kind == 0
}

/** Rich inspection result for local .wp2app files before uploading to the watch. */
data class PackageInspection(
    val header: PackageHeader,
    val abi: Int,
    val abiMinor: Int,
    val imageSize: Long,
    val relocCount: Long,
    val totalBytes: Int,
    val headerCrcOk: Boolean,
    val imageCrcOk: Boolean,
) {
    val crcValid: Boolean get() = headerCrcOk && imageCrcOk
    val abiText: String get() = "$abi.$abiMinor"
}

sealed class InstallError(message: String) : Exception(message) {
    data object Busy : InstallError("An app or the Apps menu is open on the watch — close it and try again")
    data class NoFreeSlot(val slots: Int) : InstallError("All $slots slots on the watch are full")
    data object NotAPackage : InstallError("File is not a valid .wp2app package")
    data class Refused(val status: Int) : InstallError("Watch refused installation (code %02X)".format(status))
}

fun formatVersion(v: Long): String =
    if (v >= 1024) "${v shr 20}.${(v shr 10) and 1023}.${v and 1023}" else "$v"

fun parseHeader(d: ByteArray): PackageHeader? {
    if (d.size < HEADER_SIZE || !d.copyOf(4).contentEquals("WP2A".toByteArray())) return null
    val name = d.copyOfRange(8, 32).takeWhile { it != 0.toByte() }.toByteArray()
    return PackageHeader(d.u8(6), String(name), d.le32(32), d.le32(48))
}

fun inspectPackage(d: ByteArray): PackageInspection? {
    val header = parseHeader(d) ?: return null
    if (header.kind != 0 && header.kind != 1) return null
    val abi = d.le16(4)
    val abiMinor = d.u8(7)
    val imageSize = d.le32(36)
    val relocCount = d.le32(44)
    val expectedImageCrc = d.le32(48)
    val expectedHeaderCrc = d.le32(52)
    val actualHeaderCrc = crc32(d.copyOfRange(0, HEADER_CRC_SPAN))
    val actualImageCrc = if (d.size > HEADER_SIZE) crc32(d.copyOfRange(HEADER_SIZE, d.size)) else 0L
    return PackageInspection(
        header = header,
        abi = abi,
        abiMinor = abiMinor,
        imageSize = imageSize,
        relocCount = relocCount,
        totalBytes = d.size,
        headerCrcOk = actualHeaderCrc == expectedHeaderCrc,
        imageCrcOk = d.size > HEADER_SIZE && actualImageCrc == expectedImageCrc,
    )
}

private fun check(r: ByteArray): ByteArray {
    if (r.isEmpty()) throw InstallError.Refused(0xFF)
    if (r.u8(0) == Status.BUSY.code) throw InstallError.Busy
    if (r.u8(0) != Status.OK.code) throw InstallError.Refused(r.u8(0))
    return r.copyOfRange(1, r.size)
}

suspend fun installed(s: Requester, slots: Int): Map<Int, PackageHeader> {
    val out = sortedMapOf<Int, PackageHeader>()
    for (slot in (0 until slots) + LOADER_SLOT) {
        parseHeader(check(s.call(Cmd.INFO, bytes(slot))))?.let { out[slot] = it }
    }
    return out
}

/**
 * Installs a .wp2app package.
 * If [targetSlot] is null, picks the existing slot with the same package name, or the first free slot
 * (and always [LOADER_SLOT] for App Loader packages with kind == 1).
 */
suspend fun install(
    s: Requester,
    pkg: ByteArray,
    targetSlot: Int? = null,
    progress: (Double) -> Unit,
): Int {
    val header = parseHeader(pkg)?.takeIf { it.kind == 0 || it.kind == 1 }
        ?: throw InstallError.NotAPackage
    val slot = if (header.kind == 1) {
        LOADER_SLOT
    } else if (targetSlot != null) {
        targetSlot
    } else {
        val slots = appSlots(stubVersion(s))
        val used = installed(s, slots)
        used.entries.firstOrNull { it.key < slots && it.value.name == header.name }?.key
            ?: (0 until slots).firstOrNull { it !in used }
            ?: throw InstallError.NoFreeSlot(slots)
    }

    check(s.call(Cmd.BEGIN, buf { u8(slot); le32(pkg.size.toLong()); le32(crc32(pkg)) }))
    for (off in pkg.indices step DATA_MAX) {
        val end = minOf(off + DATA_MAX, pkg.size)
        val r = check(s.call(Cmd.DATA, buf { le32(off.toLong()); add(pkg.copyOfRange(off, end)) }))
        if (r.size != 4 || r.le32(0) != end.toLong()) throw InstallError.Refused(0xFE)
        progress(end.toDouble() / pkg.size)
    }
    check(s.call(Cmd.END, bytes(0)))
    return slot
}

suspend fun remove(s: Requester, slot: Int) {
    check(s.call(Cmd.DELETE, bytes(slot)))
}
