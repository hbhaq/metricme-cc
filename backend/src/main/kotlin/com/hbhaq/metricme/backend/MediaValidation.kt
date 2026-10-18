package com.hbhaq.metricme.backend

import com.hbhaq.metricme.contracts.*
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.imageio.ImageIO

object MediaValidation {
    fun decode(request: EstimateRequest, kind: String): ByteArray {
        if (!request.consented || request.consentVersion != CONSENT_VERSION) throw ApiFailure("consent_required",400)
        if (request.context.length > 2000 || request.mediaBase64.length > (MAX_MEDIA+2)/3*4) throw ApiFailure("upload_too_large",413)
        val bytes = try { Base64.getDecoder().decode(request.mediaBase64) } catch (_: IllegalArgumentException) { throw ApiFailure("invalid_media",400) }
        if (bytes.isEmpty() || bytes.size > MAX_MEDIA) throw ApiFailure("upload_too_large",413)
        if (kind == "FOOD") image(bytes,request.mimeType) else audio(bytes,request.mimeType)
        return bytes
    }
    fun image(bytes: ByteArray,mime: String) {
        if (mime !in listOf("image/jpeg","image/png")) throw ApiFailure("unsupported_media",415)
        val jpeg = bytes.size>=3 && bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte() && bytes[2]==0xff.toByte()
        val png = bytes.size>=8 && bytes.take(8)==listOf(137,80,78,71,13,10,26,10).map(Int::toByte)
        if (!(mime=="image/jpeg" && jpeg || mime=="image/png" && png)) throw ApiFailure("invalid_media",400)
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                val readers=ImageIO.getImageReaders(stream); if(!readers.hasNext()) throw ApiFailure("invalid_media",400)
                val reader=readers.next(); try { reader.input=stream; val w=reader.getWidth(0); val h=reader.getHeight(0); if(w<16 || h<16 || w.toLong()*h>16000000) throw ApiFailure("invalid_dimensions",400); requireNotNull(reader.read(0)) } finally { reader.dispose() }
            }
        } catch(e: ApiFailure) { throw e } catch(_: Exception) { throw ApiFailure("invalid_media",400) }
    }
    fun audio(bytes: ByteArray,mime: String) {
        if(mime!="audio/wav") throw ApiFailure("unsupported_media",415)
        if(bytes.size<46) throw ApiFailure("invalid_audio",400)
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun ascii(at:Int,n:Int)=String(bytes,at,n,Charsets.US_ASCII)
        // Deliberately accept our bounded, canonical PCM recorder format only.
        if(ascii(0,4)!="RIFF" || ascii(8,4)!="WAVE" || ascii(12,4)!="fmt " || b.getInt(16)!=16 || b.getShort(20).toInt()!=1 || b.getShort(22).toInt()!=1 || b.getInt(24)!=16000 || b.getInt(28)!=32000 || b.getShort(32).toInt()!=2 || b.getShort(34).toInt()!=16 || ascii(36,4)!="data" || b.getInt(40)!=bytes.size-44 || b.getInt(4)!=bytes.size-8 || (bytes.size-44)%2!=0 || bytes.size-44>120*32000) throw ApiFailure("invalid_audio",400)
    }
}
