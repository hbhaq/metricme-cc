package com.hbhaq.metricme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.hbhaq.metricme.ai.MediaFiles
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class MediaFilesTest {
    @Test fun photoImportNormalizesOrientationAndRemovesLocationMetadata()=runBlocking<Unit> {
        val files=MediaFiles(InstrumentationRegistry.getInstrumentation().targetContext)
        val raw=files.newFile("jpg"); var result:String?=null
        try {
            val bitmap=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888)
            raw.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,90,it) }; bitmap.recycle()
            ExifInterface(raw.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString()); setAttribute(ExifInterface.TAG_GPS_LATITUDE,"10/1,0/1,0/1"); setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,"N"); saveAttributes() }
            val normalized=files.importPhoto(files.uri(raw)); result=normalized.path
            val size=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(normalized.path,size)
            assertEquals(80,size.outWidth); assertEquals(120,size.outHeight)
            val exif=ExifInterface(normalized.path); assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)); assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF))
            assertTrue(normalized.length()>0)
        } finally { files.delete(raw.path); files.delete(result) }
    }
    @Test fun invalidPhotoDoesNotLeaveImportedFilesAndPathsStayPrivate()=runBlocking<Unit> {
        val files=MediaFiles(InstrumentationRegistry.getInstrumentation().targetContext)
        val raw=files.newFile("jpg"); raw.writeText("not an image"); val before=files.files().map { it.path }.toSet()
        try {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { files.importPhoto(files.uri(raw)) } }
            assertEquals(before,files.files().map { it.path }.toSet())
            assertThrows(IllegalArgumentException::class.java) { files.owned("/sdcard/not-private.jpg") }
        } finally { files.delete(raw.path) }
    }
}
