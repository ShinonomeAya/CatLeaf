package dev.local.catleaf.nativeapp.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** 空间估算显示：小值不能被 MB 精度吞成「0.0 MB」（收藏正文几十 KB 也得如实可见） */
class StorageFormatTest {
    @Test
    fun adaptive_units() {
        assertEquals("500 B", formatSize(500))
        assertEquals("45 KB", formatSize(45L * 1024))
        assertEquals("922 KB", formatSize(943718)) // 差一点到 1 MiB，仍走 KB——不会显示成 0.9/1.0 的错觉
        assertEquals("1.0 MB", formatSize(1048576))
        assertEquals("100.0 MB", formatSize(100L * 1048576))
    }
}
