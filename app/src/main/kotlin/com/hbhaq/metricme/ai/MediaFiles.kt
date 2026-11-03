package com.hbhaq.metricme.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.*
import android.net.Uri
import androidx.core.content.FileProvider
import com.hbhaq.metricme.contracts.MAX_MEDIA
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class MediaFiles @Inject constructor(@param:ApplicationContext private val context:Context) {
    private val directory get()=File(context.filesDir,"ai-media").apply { mkdirs() }
    fun newFile(extension:String)=File(directory,"${UUID.randomUUID()}.$extension")
    fun uri(file:File):Uri=FileProvider.getUriForFile(context,"${context.packageName}.media",file)
    fun owned(path:String):File { val file=File(path).canonicalFile; require(file.parentFile==directory.canonicalFile) { "Invalid private media reference." }; return file }
    fun delete(path:String?) { if(path!=null) runCatching { owned(path).delete() } }
    fun files()=directory.listFiles().orEmpty().toList()
    suspend fun importAudio(uri:Uri):File=withContext(Dispatchers.IO) {
        val raw=newFile("audio-input")
        val output=newFile("wav")
        val extractor=MediaExtractor()
        var codec:MediaCodec?=null
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                raw.outputStream().use { out ->
                    val buffer=ByteArray(8192); var total=0
                    while(true) { val n=input.read(buffer); if(n<0) break; total+=n; require(total<=25_000_000); out.write(buffer,0,n) }
                }
            }
            extractor.setDataSource(raw.path)
            val track=(0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true } ?: error("No audio track")
            extractor.selectTrack(track)
            val format=extractor.getTrackFormat(track)
            if(format.containsKey(MediaFormat.KEY_DURATION)) require(format.getLong(MediaFormat.KEY_DURATION)<=80_000_000)
            val decoder=MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec=decoder
            decoder.configure(format,null,null,0); decoder.start()
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var inputDone=false; var outputDone=false; var frame=0L; var written=0L; var count=0; var idle=0
            val info=MediaCodec.BufferInfo()
            RandomAccessFile(output,"rw").use { out ->
                out.write(ByteArray(44))
                while(!outputDone) {
                    currentCoroutineContext().ensureActive()
                    var progress=false
                    if(!inputDone) {
                        val index=decoder.dequeueInputBuffer(10_000)
                        if(index>=0) {
                            val buffer=requireNotNull(decoder.getInputBuffer(index)); buffer.clear()
                            val n=extractor.readSampleData(buffer,0)
                            if(n<0) { decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true }
                            else { decoder.queueInputBuffer(index,0,n,extractor.sampleTime,0); extractor.advance() }
                            progress=true
                        }
                    }
                    val index=decoder.dequeueOutputBuffer(info,10_000)
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val decoded=decoder.outputFormat
                        rate=decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels=decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(rate in 8000..192000 && channels in 1..8)
                        if(decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) require(decoded.getInteger(MediaFormat.KEY_PCM_ENCODING)==AudioFormat.ENCODING_PCM_16BIT)
                        progress=true
                    } else if(index>=0) {
                        try {
                            val buffer=requireNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset+info.size)
                            val bytes=java.io.ByteArrayOutputStream()
                            while(buffer.remaining()>=channels*2) {
                                var sum=0; repeat(channels) { sum+=buffer.short.toInt() }
                                val value=sum/channels
                                val target=((frame+1)*16000/rate)
                                while(written<target) { require(count+2<=80*32000); bytes.write(value and 255); bytes.write((value shr 8) and 255); written++; count+=2 }
                                frame++
                            }
                            out.write(bytes.toByteArray())
                            outputDone=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { decoder.releaseOutputBuffer(index,false) }
                        progress=true
                    }
                    idle=if(progress) 0 else idle+1
                    require(idle<500) { "Audio decoder timed out" }
                }
                require(count>=3200)
                val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply { put("RIFF".toByteArray()); putInt(count+36); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16); put("data".toByteArray()); putInt(count) }
                out.seek(0); out.write(header.array())
            }
            output
        } catch(e:Exception) { output.delete(); throw e }
        finally { runCatching { codec?.stop() }; codec?.release(); extractor.release(); raw.delete() }
    }
    suspend fun importPhoto(uri:Uri):File=withContext(Dispatchers.IO) {
        val raw=newFile("input"); val output=newFile("jpg")
        try {
            context.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "Photo could not be opened." }; raw.outputStream().use { out -> val buffer=ByteArray(8192); var total=0; while(true) { val n=input.read(buffer); if(n<0) break; total+=n; require(total<=MAX_MEDIA) { "Choose an image smaller than 10 MiB." }; out.write(buffer,0,n) } } }
            val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(raw.path,options)
            require(options.outWidth>=16 && options.outHeight>=16 && options.outWidth.toLong()*options.outHeight<=40000000) { "Choose a valid photo up to 40 megapixels." }
            options.inJustDecodeBounds=false; options.inSampleSize=1; while(maxOf(options.outWidth,options.outHeight)/options.inSampleSize>2048) options.inSampleSize*=2
            val bitmap=requireNotNull(BitmapFactory.decodeFile(raw.path,options)) { "Unsupported or damaged photo." }
            val orientation=runCatching { ExifInterface(raw.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix=Matrix().apply { when(orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f); ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f); ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f,1f); ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f,-1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f,1f) }; ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f,1f) }
            } }
            val normalized=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
            try { output.outputStream().use { require(normalized.compress(Bitmap.CompressFormat.JPEG,85,it)) }; require(output.length() in 1..MAX_MEDIA.toLong()) } finally { if(normalized!==bitmap) normalized.recycle(); bitmap.recycle() }
            output
        } catch(e:Exception) { output.delete(); throw e } finally { raw.delete() }
    }
}
data class RecordingState(val active:Boolean=false,val seconds:Int=0,val path:String?=null,val error:String?=null)
@Singleton class LocalAudioRecorder @Inject constructor(private val files:MediaFiles) {
    val state=MutableStateFlow(RecordingState())
    private var recording:AudioRecord?=null
    private var task:Job?=null
    @Volatile private var discardRequested=false
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @android.annotation.SuppressLint("MissingPermission") // Caller requests and checks RECORD_AUDIO; revocation/errors are handled.
    @Synchronized fun start(maxSeconds:Int=120) {
        require(maxSeconds in 1..120)
        check(task?.isActive!=true)
        discardRequested=false
        state.value.path?.let(files::delete)
        val file=files.newFile("wav")
        val minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        require(minimum>0) { "Recording is unavailable on this device." }
        val audio=AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum,4096)*2)
        if(audio.state!=AudioRecord.STATE_INITIALIZED) { audio.release(); error("Microphone could not be initialized.") }
        recording=audio; state.value=RecordingState(active=true)
        task=scope.launch {
            var count=0; var error:String?=null
            try {
                RandomAccessFile(file,"rw").use { out ->
                    out.write(ByteArray(44)); audio.startRecording(); val buffer=ByteArray(4096)
                    while(isActive && state.value.active && count<maxSeconds*32000) {
                        val n=audio.read(buffer,0,minOf(buffer.size,maxSeconds*32000-count))
                        if(n<=0) { if(!state.value.active) break; error("Recording was interrupted.") }
                        out.write(buffer,0,n); count+=n; state.value=state.value.copy(seconds=count/32000)
                    }
                    val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply { put("RIFF".toByteArray()); putInt(count+36); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16); put("data".toByteArray()); putInt(count) }
                    out.seek(0); out.write(header.array())
                }
            } catch(_: Exception) { error="Recording stopped unexpectedly. Please record again." }
            finally { runCatching { audio.stop() }; audio.release(); recording=null; if(count<3200 || error!=null || discardRequested) file.delete(); state.value=if(discardRequested) RecordingState() else RecordingState(false,count/32000,if(file.exists()) file.path else null,error) }
        }
    }
    @Synchronized fun stop() { state.value=state.value.copy(active=false); runCatching { recording?.stop() } }
    fun consume() { state.value=RecordingState() }
    fun discard() { discardRequested=true; stop(); state.value.path?.let(files::delete); state.value=RecordingState() }
}
