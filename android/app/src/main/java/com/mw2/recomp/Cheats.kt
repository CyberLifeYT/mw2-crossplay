package com.mw2.recomp

import java.io.File
import java.util.zip.CRC32

/**
 * Multiplayer cheats, the phone's version of the PC launcher's PROFILES
 * screen: rank, prestige and unlocks live in the stats files under saves/
 * (8188-byte LE buffer after a big-endian CRC-32), same bytes on every
 * platform. Every profile's copies are patched at once; a .backup keeps the
 * first version. Restart the match afterwards: the title reads stats at load.
 */
internal object Cheats {

    private fun u32le(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 255) or ((b[at + 1].toInt() and 255) shl 8) or
            ((b[at + 2].toInt() and 255) shl 16) or ((b[at + 3].toInt() and 255) shl 24)

    private fun put32le(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun statFiles(saves: File): List<File> {
        if (!saves.isDirectory) return emptyList()
        return saves.walkTopDown().filter {
            it.isFile && (it.name == "mpdata" || it.name.startsWith("mpdata_"))
        }.toList()
    }

    /** Patch every stats file found; returns files changed or throws. */
    private fun patchAll(saves: File, change: (ByteArray, Int) -> Unit): Int {
        var changed = 0
        for (file in statFiles(saves)) {
            val bytes = file.readBytes()
            val lead = when (bytes.size) {
                MpCheatsData.FILE_SIZE -> 0
                MpCheatsData.FILE_SIZE + 5 -> 4
                else -> continue
            }
            val base = lead + 4
            if (u32le(bytes, base) != MpCheatsData.VERSION) continue
            if (u32le(bytes, base + 4) != MpCheatsData.FORMAT) continue
            change(bytes, base)
            val crc = CRC32()
            crc.update(bytes, base, MpCheatsData.FILE_SIZE - 4)
            val value = crc.value.toInt()
            for (i in 0 until 4) bytes[lead + i] = (value ushr (24 - 8 * i)).toByte()
            val backup = File(file.parent, file.name + ".backup")
            if (!backup.exists()) file.copyTo(backup)
            val part = File(file.parent, file.name + ".part")
            part.writeBytes(bytes)
            if (!part.renameTo(file)) throw IllegalStateException("Cannot write ${file.name}")
            changed++
        }
        if (changed == 0) throw IllegalStateException("No stats files under saves/")
        return changed
    }

    fun maxRank(saves: File): Int = patchAll(saves) { b, base ->
        put32le(b, base + MpCheatsData.EXP, MpCheatsData.MAX_EXP)
    }

    fun prestigeUp(saves: File): Int = patchAll(saves) { b, base ->
        val cur = u32le(b, base + MpCheatsData.PRESTIGE)
        put32le(b, base + MpCheatsData.PRESTIGE, (cur + 1) % (MpCheatsData.MAX_PRESTIGE + 1))
    }

    fun unlockEverything(saves: File): Int = patchAll(saves) { b, base ->
        val ch = MpCheatsData.CHALLENGES
        var i = 0
        while (i + 2 < ch.size) {
            val index = ch[i]
            val tiers = ch[i + 1]
            val target = ch[i + 2]
            b[base + MpCheatsData.CHALLENGE_STATE + index] = (tiers + 1).toByte()
            put32le(b, base + MpCheatsData.CHALLENGE_PROGRESS + 4 * index, target)
            i += 3
        }
        for (index in MpCheatsData.TITLES) {
            val at = base + MpCheatsData.TITLE_UNLOCKED + index / 8
            b[at] = (b[at].toInt() or (1 shl (index % 8))).toByte()
        }
        for (index in MpCheatsData.ICONS) {
            val at = base + MpCheatsData.ICON_UNLOCKED + index / 8
            b[at] = (b[at].toInt() or (1 shl (index % 8))).toByte()
        }
        for (index in MpCheatsData.KILLSTREAKS) {
            val at = base + MpCheatsData.KILLSTREAK_UNLOCKED + index / 8
            b[at] = (b[at].toInt() or (1 shl (index % 8))).toByte()
        }
    }
}
