package expo.modules.blueskyvideo

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Remembers where the audio written to the sink ends, so silence can continue from there. */
@UnstableApi
internal class EndTrackingAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    var pcmFormat: Format? = null
        private set
    var writtenEndUs = C.TIME_UNSET
        private set

    /**
     * When false, a buffer other than the one the sink has partly consumed is refused, so the
     * renderer can stop writing to the sink at a buffer boundary (the sink requires a partly consumed
     * buffer to be passed again until it's consumed).
     */
    var acceptNewBuffers = true

    /** Whether the sink holds a buffer it has only partly consumed. */
    val hasPartialBuffer: Boolean
        get() = pendingBufferStartUs != C.TIME_UNSET

    private var pendingBufferStartUs = C.TIME_UNSET
    private var pendingBufferBytes = 0

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        pcmFormat = if (inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW) inputFormat else null
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (presentationTimeUs != pendingBufferStartUs) {
            if (!acceptNewBuffers) {
                return false
            }
            pendingBufferStartUs = presentationTimeUs
            pendingBufferBytes = buffer.remaining()
        }
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (handled) {
            val format = pcmFormat
            if (format != null) {
                val bytesPerFrame = format.channelCount * 2
                writtenEndUs =
                    presentationTimeUs + pendingBufferBytes / bytesPerFrame * C.MICROS_PER_SECOND / format.sampleRate
            }
            pendingBufferStartUs = C.TIME_UNSET
        }
        return handled
    }

    override fun flush() {
        writtenEndUs = C.TIME_UNSET
        acceptNewBuffers = true
        pendingBufferStartUs = C.TIME_UNSET
        super.flush()
    }
}

/**
 * Creates a codec adapter ahead of time on a background thread, and hands it out when the
 * renderer asks for one with the same codec. Creating, configuring and starting a decoder on the
 * playback thread blocks it, and the video renderer with it, for 100-200 ms.
 */
@UnstableApi
internal class PrewarmingCodecAdapterFactory(private val delegate: MediaCodecAdapter.Factory) : MediaCodecAdapter.Factory {
    private var prewarmed: Future<MediaCodecAdapter>? = null
    private var prewarmedCodecName: String? = null

    fun prewarm(configuration: MediaCodecAdapter.Configuration) {
        discard()
        prewarmedCodecName = configuration.codecInfo.name
        prewarmed = executor.submit<MediaCodecAdapter> { delegate.createAdapter(configuration) }
    }

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val future = prewarmed
        val name = prewarmedCodecName
        prewarmed = null
        prewarmedCodecName = null
        if (future != null) {
            val adapter = runCatching { future.get() }.getOrNull()
            if (adapter != null && name == configuration.codecInfo.name) {
                return adapter
            }
            adapter?.release()
        }
        return delegate.createAdapter(configuration)
    }

    fun discard() {
        val future = prewarmed ?: return
        prewarmed = null
        prewarmedCodecName = null
        executor.execute { runCatching { future.get().release() } }
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "BlueskyVideoCodecPrewarm") }
    }
}

/**
 * An audio renderer that doesn't decode while [muted]. The audio track stays selected, so the
 * player keeps reading it from the same media segments as the video, and unmuting doesn't make it
 * reload them.
 *
 * While muted no codec is created (MediaCodecRenderer then skips samples up to the playback
 * position) and the audio sink is kept as full of silence as it would be of decoded audio, so its
 * audio track keeps playing and keeps driving the playback clock. On unmute, silence is written a little further ahead, the encoded
 * samples are skipped up to where it ends, and decoded audio continues from there, so the video
 * doesn't wait for a new audio track to start.
 */
@UnstableApi
internal class SkipWhileMutedAudioRenderer(
    context: Context,
    private val codecFactory: PrewarmingCodecAdapterFactory,
    private val codecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    private val sink: EndTrackingAudioSink,
) : MediaCodecAudioRenderer(
        context,
        codecFactory,
        codecSelector,
        enableDecoderFallback,
        eventHandler,
        eventListener,
        sink,
    ) {
    /** Set from the main thread, applied on the playback thread in [render]. */
    @Volatile var muted = true

    private var skipping = false
    private var inputFormat: Format? = null
    private var silenceNextUs = C.TIME_UNSET
    private var silenceChunk: ByteBuffer? = null
    private var silenceChunkPending = false
    private var unmuteTargetUs = C.TIME_UNSET
    private var prewarmPending = false
    private var skipStartPositionUs = C.TIME_UNSET
    private var lastCodecInfo: MediaCodecInfo? = null

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        inputFormat = formatHolder.format
        return super.onInputFormatChanged(formatHolder)
    }

    override fun shouldInitCodec(codecInfo: MediaCodecInfo): Boolean = !skipping && super.shouldInitCodec(codecInfo)

    // Without a codec, MediaCodecRenderer checks whether bypass is possible on every render, and
    // the audio sink answers that with two binder calls to audioserver (offload support and stream
    // type), ~1 ms each. Nothing is decoded or bypassed while skipping, so don't ask.
    override fun shouldUseBypass(format: Format): Boolean = !skipping && super.shouldUseBypass(format)

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (muted && !skipping) {
            // Let the sink finish the decoded buffer it holds, then stop decoding.
            sink.acceptNewBuffers = false
            if (!sink.hasPartialBuffer) {
                getCodecInfo()?.let { lastCodecInfo = it }
                releaseCodec()
                prewarmPending = true
                sink.acceptNewBuffers = true
                skipping = true
                skipStartPositionUs = positionUs
                unmuteTargetUs = C.TIME_UNSET
                silenceNextUs = if (sink.writtenEndUs != C.TIME_UNSET) sink.writtenEndUs else positionUs
            }
        } else if (!muted && !skipping) {
            sink.acceptNewBuffers = true
        } else if (!muted && skipping && unmuteTargetUs == C.TIME_UNSET) {
            unmuteTargetUs = maxOf(silenceNextUs, positionUs + UNMUTE_LEAD_US)
        }

        super.render(positionUs, elapsedRealtimeUs)

        if (skipping && prewarmPending && inputFormat != null) {
            prewarmPending = false
            prewarmCodec(inputFormat!!)
        }
        if (skipping) {
            // The audio track only starts once its buffer is full; after that, a short lead keeps it
            // playing and lets decoded audio follow soon after unmuting.
            val audioTrackRunning = positionUs > skipStartPositionUs
            val targetUs =
                when {
                    unmuteTargetUs != C.TIME_UNSET -> unmuteTargetUs
                    audioTrackRunning -> positionUs + MUTED_LEAD_US
                    else -> Long.MAX_VALUE
                }
            writeSilence(targetUs)
            if (unmuteTargetUs != C.TIME_UNSET && !silenceChunkPending && silenceNextUs >= unmuteTargetUs) {
                // Decode from where the silence ends.
                skipSource(silenceNextUs)
                skipping = false
                unmuteTargetUs = C.TIME_UNSET
            }
        }
    }

    private fun writeSilence(untilUs: Long) {
        val format = inputFormat ?: return
        if (sink.pcmFormat == null) {
            sink.configure(
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setPcmEncoding(C.ENCODING_PCM_16BIT)
                    .setChannelCount(format.channelCount)
                    .setSampleRate(format.sampleRate)
                    .build(),
                /* specifiedBufferSize= */ 0,
                /* outputChannels= */ null,
            )
        }
        val pcm = sink.pcmFormat ?: return
        val frames = pcm.sampleRate / CHUNKS_PER_SECOND
        val chunkUs = frames * C.MICROS_PER_SECOND / pcm.sampleRate
        val chunkBytes = frames * pcm.channelCount * 2
        var chunk = silenceChunk
        if (chunk == null || chunk.capacity() != chunkBytes) {
            chunk = ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.nativeOrder())
            silenceChunk = chunk
            silenceChunkPending = false
        }
        // A chunk the sink has partly consumed must be passed again until it's consumed.
        while (silenceChunkPending || silenceNextUs < untilUs) {
            if (!silenceChunkPending) {
                chunk!!.clear()
                silenceChunkPending = true
            }
            if (!sink.handleBuffer(chunk!!, silenceNextUs, 1)) {
                return
            }
            silenceChunkPending = false
            silenceNextUs += chunkUs
        }
    }

    /** Starts creating the codec unmuting will need, see [PrewarmingCodecAdapterFactory]. */
    private fun prewarmCodec(format: Format) {
        val codecInfo =
            lastCodecInfo
                ?: runCatching { getDecoderInfos(codecSelector, format, false).firstOrNull() }.getOrNull()
                ?: return
        var operatingRate = getCodecOperatingRateV23(1f, format, streamFormats)
        if (operatingRate <= ASSUMED_MINIMUM_CODEC_OPERATING_RATE) {
            operatingRate = CODEC_OPERATING_RATE_UNSET
        }
        codecFactory.prewarm(getMediaCodecConfiguration(codecInfo, format, null, operatingRate))
    }

    override fun isReady(): Boolean = super.isReady() || (skipping && isReadyForDecoding())

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
        silenceNextUs = positionUs
        skipStartPositionUs = positionUs
        silenceChunkPending = false
        unmuteTargetUs = C.TIME_UNSET
    }

    override fun onDisabled() {
        codecFactory.discard()
        prewarmPending = false
        skipping = false
        unmuteTargetUs = C.TIME_UNSET
        silenceChunkPending = false
        inputFormat = null
        super.onDisabled()
    }

    private companion object {
        /** The value MediaCodecAudioRenderer passes to MediaCodecRenderer. */
        const val ASSUMED_MINIMUM_CODEC_OPERATING_RATE = 44100f
        const val CHUNKS_PER_SECOND = 50
        /** How far ahead of the playback position silence is kept while muted. */
        const val MUTED_LEAD_US = 150_000L
        /** How far ahead decoded audio starts after unmuting, enough to decode the first samples. */
        const val UNMUTE_LEAD_US = 150_000L
    }
}
