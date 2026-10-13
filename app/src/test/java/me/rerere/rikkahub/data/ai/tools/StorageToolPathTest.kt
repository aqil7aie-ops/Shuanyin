package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageToolPathTest {
    @Test
    fun `normal relative paths are normalized`() {
        assertEquals("notes/today.txt", normalizeStorageRelativePath("notes\\today.txt"))
        assertEquals("report.txt", normalizeStorageRelativePath(" report.txt "))
    }

    @Test
    fun `blank root path is allowed for listing`() {
        assertEquals("", normalizeStorageRelativePath(""))
        assertEquals("", normalizeStorageRelativePath("."))
    }

    @Test
    fun `parent segments and absolute paths are rejected`() {
        assertNull(normalizeStorageRelativePath("../secret.txt"))
        assertNull(normalizeStorageRelativePath("notes/../secret.txt"))
        assertNull(normalizeStorageRelativePath("/sdcard/secret.txt"))
        assertNull(normalizeStorageRelativePath("C:\\secret.txt"))
    }

    @Test
    fun `empty interior path segments are rejected`() {
        assertNull(normalizeStorageRelativePath("notes//file.txt"))
    }
}
