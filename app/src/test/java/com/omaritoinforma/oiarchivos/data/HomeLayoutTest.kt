package com.omaritoinforma.oiarchivos.data

import org.junit.Assert.*
import org.junit.Test

class HomeLayoutTest {
    @Test
    fun withoutSettingsEverythingShowsInFactoryOrder() {
        assertEquals(HomeSection.entries.toList(), HomeLayout.visible(emptyList(), emptySet()))
        assertEquals(FileCategory.entries.toList(), HomeLayout.categories(emptySet()))
        assertEquals(QuickTile.entries.toList(), HomeLayout.quick(emptySet()))
    }

    @Test
    fun storedOrderComesFirstAndNewSectionsAreAppended() {
        val order = HomeLayout.order(listOf("QUICK", "STORAGE", "NO_EXISTE", "QUICK"))
        assertEquals(listOf(HomeSection.QUICK, HomeSection.STORAGE), order.take(2))
        assertEquals(HomeSection.entries.size, order.size)
        assertEquals(order.size, order.toSet().size)
    }

    @Test
    fun hiddenSectionsDisappearButKeepTheirPlace() {
        val visible = HomeLayout.visible(listOf("BOOKMARKS", "CATEGORIES"), setOf("BOOKMARKS", "INVENTADA"))
        assertFalse(HomeSection.BOOKMARKS in visible)
        assertEquals(HomeSection.CATEGORIES, visible.first())
        assertEquals(HomeSection.entries.size - 1, visible.size)
    }

    @Test
    fun everythingCanBeHiddenWithoutErrors() {
        assertTrue(HomeLayout.visible(emptyList(), HomeSection.entries.map { it.name }.toSet()).isEmpty())
        val all = HomeLayout.tileKeys
        assertTrue(HomeLayout.categories(all).isEmpty())
        assertTrue(HomeLayout.quick(all).isEmpty())
    }

    @Test
    fun movingStopsAtTheEnds() {
        val down = HomeLayout.move(emptyList(), HomeSection.STORAGE, 1)
        assertEquals(listOf("CATEGORIES", "STORAGE"), down.take(2))
        val factory = HomeLayout.order(emptyList()).map { it.name }
        assertEquals(factory, HomeLayout.move(emptyList(), HomeSection.STORAGE, -1))
        assertEquals(factory, HomeLayout.move(emptyList(), HomeSection.entries.last(), 1))
    }

    @Test
    fun tileKeysAreUniqueAndHideOneTileAtATime() {
        assertEquals(FileCategory.entries.size + QuickTile.entries.size, HomeLayout.tileKeys.size)
        val hidden = setOf(HomeLayout.categoryKey(FileCategory.MUSIC), HomeLayout.quickKey(QuickTile.ROOT))
        assertFalse(FileCategory.MUSIC in HomeLayout.categories(hidden))
        assertEquals(FileCategory.entries.size - 1, HomeLayout.categories(hidden).size)
        assertFalse(QuickTile.ROOT in HomeLayout.quick(hidden))
        assertTrue(QuickTile.DOWNLOADS in HomeLayout.quick(hidden))
    }

    @Test
    fun theBackupOnlyAcceptsKnownSectionsAndTiles() {
        val ok = """{"formato":"OI Archivos ajustes","version":1,"ajustes":{"home_order":"QUICK,STORAGE","home_hidden":"BOOKMARKS","home_hidden_tiles":"cat:MUSIC,quick:ROOT"}}"""
        val parsed = SettingsBackup.parse(ok)
        assertEquals("QUICK,STORAGE", parsed["home_order"])
        assertEquals("cat:MUSIC,quick:ROOT", parsed["home_hidden_tiles"])
        assertThrows(java.io.IOException::class.java) { SettingsBackup.parse(ok.replace("BOOKMARKS", "AJUSTES")) }
        assertThrows(java.io.IOException::class.java) { SettingsBackup.parse(ok.replace("cat:MUSIC", "cat:NADA")) }
    }

    private fun item(path: String, dir: Boolean = false) =
        FileItem(java.io.File(path), path.substringAfterLast('/'), path, dir, 1, 0, path.substringAfterLast('/').startsWith("."), -1)

    @Test
    fun newFilesSkipFoldersHiddenAndOtherAppsData() {
        val recent =
            listOf(
                item("/sdcard/Download", dir = true),
                item("/sdcard/Android/data/com.otra/cache/x.tmp"),
                item("/sdcard/Android/obb/com.otra/main.obb"),
                item("/sdcard/.thumbnails/a.jpg"),
                item("/sdcard/Download/.oculto.txt"),
                item("/sdcard/Download/uno.txt"),
                item("/sdcard/DCIM/dos.jpg"),
                item("/sdcard/Music/tres.mp3"))
        assertEquals(listOf("uno.txt", "dos.jpg", "tres.mp3"), HomeLayout.newFiles(recent).map { it.name })
    }

    @Test
    fun newFilesAreLimitedKeepingTheMostRecentFirst() {
        val recent = (1..20).map { item("/sdcard/Download/f$it.txt") }
        assertEquals(HomeLayout.NEW_FILES_COUNT, HomeLayout.newFiles(recent).size)
        assertEquals("f1.txt", HomeLayout.newFiles(recent).first().name)
        assertEquals(2, HomeLayout.newFiles(recent, 2).size)
    }

    @Test
    fun theNewFilesSectionCanBeHiddenLikeAnyOther() {
        assertTrue(HomeSection.NEW_FILES in HomeLayout.visible(emptyList(), emptySet()))
        assertFalse(HomeSection.NEW_FILES in HomeLayout.visible(emptyList(), setOf("NEW_FILES")))
    }
}
