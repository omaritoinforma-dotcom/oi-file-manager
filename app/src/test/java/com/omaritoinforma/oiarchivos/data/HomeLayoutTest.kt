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
        assertEquals(factory, HomeLayout.move(emptyList(), HomeSection.BOOKMARKS, 1))
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
}
