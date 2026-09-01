package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardBoundaryEditorInstrumentedTest {
    @Test fun largeGalleryImageIsBoundedBeforeComposeDraw() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolver = context.contentResolver
        val source = Bitmap.createBitmap(4_096, 3_072, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        @Suppress("DEPRECATION")
        val uri = Uri.parse(requireNotNull(MediaStore.Images.Media.insertImage(
            resolver,
            source,
            "domidex-grading-test-${System.nanoTime()}",
            "Temporary DomiDex grading decode test",
        )))
        source.recycle()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        assertTrue("MediaStore image bounds were ${bounds.outWidth}x${bounds.outHeight}", bounds.outWidth > 0 && bounds.outHeight > 0)

        val decoded = decodeBoundaryBitmap(resolver, uri)

        requireNotNull(decoded)
        assertTrue(maxOf(decoded.width, decoded.height) <= 2_048)
        assertTrue(decoded.width > decoded.height)
        decoded.recycle()
        resolver.delete(uri, null, null)
    }

    @Test fun unreadableGalleryImageFailsWithoutBitmap() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "grading-invalid.jpg").apply { writeText("not an image") }

        assertNull(decodeBoundaryBitmap(context.contentResolver, Uri.fromFile(file)))

        file.delete()
    }

    @Test fun exifRotationIsAppliedBeforeBoundaryEditing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "grading-exif-${System.nanoTime()}.jpg")
        val source = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        source.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val decoded = decodeBoundaryBitmap(context.contentResolver, Uri.fromFile(file))

        requireNotNull(decoded)
        assertTrue("EXIF 90° must turn portrait pixels into landscape", decoded.width > decoded.height)
        decoded.recycle()
        file.delete()
    }

    @Test fun severalGalleryImagesCanBeDecodedAndReleasedSequentially() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        repeat(4) { index ->
            val file = File(context.cacheDir, "grading-sequence-$index-${System.nanoTime()}.jpg")
            val source = Bitmap.createBitmap(2_400, 1_600, Bitmap.Config.RGB_565).apply { eraseColor(Color.DKGRAY) }
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            source.recycle()
            val decoded = decodeBoundaryBitmap(context.contentResolver, Uri.fromFile(file))
            requireNotNull(decoded)
            assertTrue(maxOf(decoded.width, decoded.height) <= 2_048)
            decoded.recycle()
            file.delete()
        }
    }
}
