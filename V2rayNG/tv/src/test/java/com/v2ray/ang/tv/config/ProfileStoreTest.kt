package com.v2ray.ang.tv.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProfileStoreTest {
    @TempDir
    lateinit var directory: File

    private fun profile(raw: String) = Profile(
        guid = ProfileStore.newGuid(),
        remarks = "node",
        protocol = "vmess",
        outbound = """{"protocol":"vmess"}""",
        raw = raw,
    )

    @Test
    fun emptyStoreHasNoSelection() {
        val snapshot = ProfileStore(directory).load()
        assertTrue(snapshot.profiles.isEmpty())
        assertNull(snapshot.selected)
    }

    @Test
    fun addPersistsProfilesAndSelectsFirstByGuid() {
        val store = ProfileStore(directory)
        val first = profile("vmess://one")
        val second = profile("vmess://two")
        assertEquals(2, store.add(listOf(first, second)))
        val loaded = store.load()
        assertEquals(2, loaded.profiles.size)
        assertEquals(first.guid, loaded.selected)
        assertEquals(first.guid, loaded.selectedProfile()?.guid)
    }

    @Test
    fun duplicateShareLinksAreNotImportedTwice() {
        val store = ProfileStore(directory)
        assertEquals(1, store.add(listOf(profile("vmess://same"))))
        assertEquals(0, store.add(listOf(profile("vmess://same"))))
        assertEquals(1, store.load().profiles.size)
    }

    @Test
    fun blankRawLinksAreAlwaysKept() {
        val store = ProfileStore(directory)
        assertEquals(1, store.add(listOf(profile(""))))
        assertEquals(1, store.add(listOf(profile(""))))
        assertEquals(2, store.load().profiles.size)
    }

    @Test
    fun removingSelectedMovesSelectionToRemainingGuid() {
        val store = ProfileStore(directory)
        val first = profile("vmess://one")
        val second = profile("vmess://two")
        store.add(listOf(first, second))
        assertTrue(store.remove(first.guid))
        val loaded = store.load()
        assertEquals(1, loaded.profiles.size)
        assertEquals(second.guid, loaded.selected)
    }

    @Test
    fun removeAndSelectRejectUnknownGuid() {
        val store = ProfileStore(directory)
        val first = profile("vmess://one")
        store.add(listOf(first))
        assertFalse(store.remove("missing-guid"))
        assertFalse(store.select("missing-guid"))
        assertEquals(first.guid, store.load().selected)
    }

    @Test
    fun selectAcceptsOnlyKnownGuid() {
        val store = ProfileStore(directory)
        val first = profile("vmess://one")
        val second = profile("vmess://two")
        store.add(listOf(first, second))
        assertTrue(store.select(second.guid))
        assertEquals(second.guid, store.load().selected)
    }

    @Test
    fun corruptedFileIsIgnoredWithoutCrashing() {
        File(directory, "profiles.json").writeText("{not json")
        val snapshot = ProfileStore(directory).load()
        assertTrue(snapshot.profiles.isEmpty())
        assertNull(snapshot.selected)
    }

    @Test
    fun futureFormatIsNotOverwritten() {
        val file = File(directory, "profiles.json")
        file.writeText("""{"version":99,"selected":null,"profiles":[]}""")
        val store = ProfileStore(directory)
        assertTrue(store.load().profiles.isEmpty())
        assertTrue(file.readText().contains("99"))
    }

    @Test
    fun invalidEntriesAreDroppedOnRead() {
        val file = File(directory, "profiles.json")
        file.writeText(
            """{"version":1,"selected":"gone","profiles":[{"guid":"","remarks":"","protocol":"","outbound":"","raw":""}]}"""
        )
        val snapshot = ProfileStore(directory).load()
        assertTrue(snapshot.profiles.isEmpty())
        assertNull(snapshot.selected)
    }
}
