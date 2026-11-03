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
    @Test fun uploadedStereoAudioIsConvertedToBoundedMonoWav()=runBlocking<Unit> {
        val files=MediaFiles(InstrumentationRegistry.getInstrumentation().targetContext)
        val raw=files.newFile("wav"); var converted:String?=null
        try {
            val samples=48000; val bytes=samples*2*2
            val header=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(bytes+36); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(48000); putInt(192000); putShort(4); putShort(16); put("data".toByteArray()); putInt(bytes)
            }
            raw.outputStream().use { out -> out.write(header.array()); val pcm=java.nio.ByteBuffer.allocate(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN); repeat(samples) { pcm.putShort(1200); pcm.putShort(800) }; out.write(pcm.array()) }
            val result=files.importAudio(files.uri(raw)); converted=result.path
            val data=result.readBytes(); val wav=java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF",String(data,0,4)); assertEquals("WAVE",String(data,8,4))
            assertEquals(1,wav.getShort(22).toInt()); assertEquals(16000,wav.getInt(24)); assertEquals(16,wav.getShort(34).toInt())
            assertEquals(32044,data.size); assertEquals(1000,wav.getShort(44).toInt())
        } finally { files.delete(raw.path); files.delete(converted) }
    }
    @Test fun invalidAudioLeavesNoImportedFiles()=runBlocking<Unit> {
        val files=MediaFiles(InstrumentationRegistry.getInstrumentation().targetContext)
        val raw=files.newFile("mp3"); raw.writeText("not audio")
        val before=files.files().map { it.path }.toSet()
        try {
            var failed=false
            try { files.importAudio(files.uri(raw)) } catch(_:Exception) { failed=true }
            assertTrue(failed); assertEquals(before,files.files().map { it.path }.toSet())
        } finally { files.delete(raw.path) }
    }
}
