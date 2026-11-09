package com.v2ray.ang.tv.config

import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * One imported server. `outbound` is an Xray outbound object without its tag; `raw` keeps
 * the original share link so a re-import does not duplicate the same server.
 */
data class Profile(
    val guid: String = "",
    val remarks: String = "",
    val protocol: String = "",
    val outbound: String = "",
    val raw: String = "",
)

data class ProfileSnapshot(
    val version: Int = 1,
    val selected: String? = null,
    val profiles: List<Profile> = emptyList(),
) {
    fun selectedProfile(): Profile? = selected?.let { id -> profiles.firstOrNull { it.guid == id } }
}

/**
 * Owns the TV client's configuration file.
 *
 * Ownership distinction from the :app module: this APK is installed separately and cannot
 * read :app's MMKV data, so it owns its own single JSON document. Selection is always by
 * server GUID, never by list position. Writes are whole-document replacements through a
 * same-directory temp file + rename, so a failed or interrupted write leaves the previous
 * release of the data intact.
 */
class ProfileStore(private val directory: File) {
    private val gson = Gson()
    private val lock = Any()
    private val file = File(directory, "profiles.json")
    private val temp = File(directory, "profiles.json.tmp")

    private data class Stored(
        val version: Int = 0,
        val selected: String? = null,
        val profiles: List<Profile>? = null,
    )

    fun load(): ProfileSnapshot = synchronized(lock) { readLocked() }

    fun add(imported: List<Profile>): Int {
        if (imported.isEmpty()) return 0
        return synchronized(lock) {
            val current = readLocked()
            val seen = current.profiles.map { it.raw }.filter { it.isNotBlank() }.toMutableSet()
            val fresh = ArrayList<Profile>()
            for (candidate in imported) {
                if (candidate.guid.isBlank() || candidate.outbound.isBlank()) continue
                if (candidate.raw.isNotBlank() && !seen.add(candidate.raw)) continue
                fresh += candidate
            }
            if (fresh.isEmpty()) return@synchronized 0
            val merged = current.profiles + fresh
            val selected = current.selected?.takeIf { id -> merged.any { it.guid == id } }
                ?: fresh.first().guid
            if (writeLocked(ProfileSnapshot(VERSION, selected, merged))) fresh.size else 0
        }
    }

    fun remove(guid: String): Boolean = synchronized(lock) {
        val current = readLocked()
        val remaining = current.profiles.filterNot { it.guid == guid }
        if (remaining.size == current.profiles.size) return@synchronized false
        val selected = if (current.selected == guid) {
            remaining.firstOrNull()?.guid
        } else {
            current.selected
        }
        writeLocked(ProfileSnapshot(VERSION, selected, remaining))
    }

    fun select(guid: String): Boolean = synchronized(lock) {
        val current = readLocked()
        if (current.profiles.none { it.guid == guid }) return@synchronized false
        writeLocked(current.copy(selected = guid))
    }

    fun find(guid: String?): Profile? {
        if (guid.isNullOrBlank()) return null
        return load().profiles.firstOrNull { it.guid == guid }
    }

    private fun readLocked(): ProfileSnapshot {
        val text = runCatching { file.readText() }.getOrNull() ?: return ProfileSnapshot()
        val stored = runCatching { gson.fromJson(text, Stored::class.java) }.getOrNull()
            ?: return ProfileSnapshot()
        // A newer on-disk format is ignored rather than rewritten, so a downgraded APK
        // cannot destroy data it does not understand.
        if (stored.version != VERSION) return ProfileSnapshot()
        val valid = stored.profiles.orEmpty().filter { it.guid.isNotBlank() && it.outbound.isNotBlank() }
        val selected = stored.selected?.takeIf { id -> valid.any { it.guid == id } }
        return ProfileSnapshot(VERSION, selected, valid)
    }

    private fun writeLocked(snapshot: ProfileSnapshot): Boolean = runCatching {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("cannot create profile directory")
        }
        temp.writeText(gson.toJson(snapshot))
        if (temp.renameTo(file)) return@runCatching true
        // File.renameTo cannot replace an existing target on every filesystem (Windows,
        // where JVM unit tests run, refuses it). Stage the previous document aside, commit
        // the new one, and restore the old document if the commit fails. On Android the
        // direct rename above is atomic and this fallback is never reached.
        val backup = File(directory, "profiles.json.bak")
        backup.delete()
        if (file.exists() && !file.renameTo(backup)) {
            temp.delete()
            throw IOException("cannot stage previous profile file")
        }
        if (temp.renameTo(file)) {
            backup.delete()
            true
        } else {
            if (backup.exists()) backup.renameTo(file)
            temp.delete()
            throw IOException("cannot replace profile file")
        }
    }.getOrDefault(false)

    companion object {
        const val VERSION = 1
        fun newGuid(): String = UUID.randomUUID().toString()
    }
}
