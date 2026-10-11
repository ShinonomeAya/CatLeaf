package dev.local.catleaf.nativeapp.ui.reading

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * V08（独立验收）：56 dp 缩略图不得整张解大图。
 * 用内存里合成的 1600×1600 PNG 验证两段式解码把展开尺寸压到目标附近；小图不放大采样。
 */
@RunWith(AndroidJUnit4::class)
class ThumbnailDecodeTest {

    private fun png(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun large_image_decodes_at_or_below_double_target() {
        val target = 168 // 56dp × 3.0 密度
        val decoded = decodeSampledThumbnail(png(1600, 1600), target)!!
        assertTrue("展开宽 ${decoded.width} 应 <336（2×目标）", decoded.width < target * 2)
        assertTrue("展开宽 ${decoded.width} 应 ≥168（够显示不糊）", decoded.width >= target)
        assertTrue("高同样受控", decoded.height < target * 2 && decoded.height >= target)
    }

    @Test
    fun small_image_is_not_upsampled() {
        val decoded = decodeSampledThumbnail(png(120, 120), 168)!!
        assertTrue("小图保持原尺寸", decoded.width == 120)
    }

    @Test
    fun non_image_bytes_return_null() {
        assertNull("解不开就 null，不崩", decodeSampledThumbnail("not a png".toByteArray(), 168))
    }
}
