package com.botts.impl.service.oscar.video;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.*;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.sensorhub.mpegts.DataBufferListener;
import org.sensorhub.mpegts.FFmpegUtils;
import org.sensorhub.mpegts.DataBufferRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE;
import static org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE;
import static org.bytedeco.ffmpeg.global.avutil.av_rescale_q;

// TODO Should this be moved to the ffmpeg driver package?
public class VideoKeyframeDecimator implements DataBufferListener {
    private static final Logger logger = LoggerFactory.getLogger(VideoKeyframeDecimator.class);
    final String outputFileName;

    final static String LISTENER_NAME = "KEYFRAME-DECIMATOR";

    boolean isWriting = true;

    final int totalKeyframe;
    long duration;
    long keyFrameDuration;
    long currentDecFrame = 0;

    AVFormatContext avFormatContext;
    AVStream avStream;
    AVRational timeBase;

    Runnable closeCallback;

    final Object lock = new Object();

    VideoKeyframeDecimator(String outputFileName, int totalKeyframe, AVStream otherStream) {
        this.outputFileName = outputFileName;
        this.totalKeyframe = totalKeyframe;

        openOutputFile(outputFileName, otherStream);

        keyFrameDuration = duration / totalKeyframe;
    }

    public void openOutputFile(String fileName, AVStream otherStream) {
        avFormatContext = new AVFormatContext(null);
        avformat.avformat_alloc_output_context2(avFormatContext, null, "mp4", null);

        try {
            avStream = avformat.avformat_new_stream(avFormatContext, null);

            // This deep-copies the extradata into a buffer owned by our codecpar, which is what
            // lets closeFile() free the context. Do not "helpfully" re-point extradata at the
            // input stream's buffer afterwards: that leaks this copy and leaves the input's
            // buffer with two owners, so freeing this context would free memory that
            // avformat_close_input() on the input then frees a second time.
            int ret = avcodec.avcodec_parameters_copy(avStream.codecpar(), otherStream.codecpar());
            if (ret < 0) {
                throw new RuntimeException("Could not copy codec parameters for file " + fileName);
            }
            FFmpegUtils.cleanCodecParameters(avStream.codecpar());

            avStream.codecpar().format(otherStream.codecpar().format());

            if ((avFormatContext.oformat().flags() & AVFMT_NOFILE) == 0) {
                AVIOContext avioContext = new AVIOContext(null);
                if (avio_open(avioContext, fileName, AVIO_FLAG_WRITE) < 0) {
                    throw new RuntimeException("Could not open file.");
                }
                avFormatContext.pb(avioContext);
            }

            avformat.avformat_write_header(avFormatContext, (AVDictionary) null);

            timeBase = avStream.time_base();
            duration = av_rescale_q(otherStream.duration(), otherStream.time_base(), avStream.time_base());
            //avStream.time_base(timeBase);
            //avStream.duration(duration);
            //avFormatContext.duration(duration);

            if (timeBase != null) {
                var frameRate = avutil.av_make_q(totalKeyframe * timeBase.den(), (int) duration * timeBase.num());
                avStream.avg_frame_rate(frameRate);
                avStream.r_frame_rate(frameRate);
            }
            else {
                avStream.avg_frame_rate(avutil.av_make_q(30, 1));
            }
        } catch (Throwable t) {
            // The constructor throwing means no one is left holding this decimator, so nothing
            // will ever call closeFile() to release the context. VideoRetention logs the failure
            // and moves on to the next file a minute later, so leaking here would accumulate just
            // as fast as the close-path leak this guards.
            isWriting = false;
            freeOutputContext();
            throw t;
        }
    }

    @Override
    public void onDataBuffer(DataBufferRecord record) {
        synchronized (lock) {
            if (!isWriting)
                return;

            //resetFrameTimeout();
            long timestamp = (long) (record.getPresentationTimestamp() * timeBase.den() / timeBase.num());


            if (record.isKeyFrame() && timestamp >= keyFrameDuration * currentDecFrame) {
                //if (timestamp >= keyFrameDuration * currentDecFrame) {
                byte[] data = record.getDataBuffer();
                AVPacket avPacket = av_packet_alloc();

                av_new_packet(avPacket, data.length);
                avPacket.data().put(data);
                avPacket.time_base();
                avPacket.stream_index(avStream.index());
                avPacket.duration(keyFrameDuration);
                long ts = keyFrameDuration * currentDecFrame;
                avPacket.pts(ts);
                avPacket.dts(ts);

                avPacket.flags(avPacket.flags() | avcodec.AV_PKT_FLAG_KEY);

                avformat.av_interleaved_write_frame(avFormatContext, avPacket);

                av_packet_free(avPacket);

                currentDecFrame++;
                if (currentDecFrame >= totalKeyframe) {
                    closeFile();
                }
            }
        }
    }

    public void closeFile() {
        synchronized (lock) {
            if (isWriting) {
                isWriting = false;
                avformat.av_write_trailer(avFormatContext);
                freeOutputContext();

                if (closeCallback != null) {
                    closeCallback.run();
                }
            }
        }
    }

    /**
     * Releases the output context and everything it owns: the AVIOContext opened in
     * {@link #openOutputFile}, the stream, and the stream's codec parameters and extradata.
     * <p>
     * Closing the AVIOContext alone used to be the whole of the cleanup, so the context itself
     * leaked on every decimation; VideoRetention decimates on a one-minute schedule, which is
     * tens of thousands of leaked contexts a month on the node.
     * <p>
     * Callers must hold {@link #lock} and must have already cleared {@link #isWriting}, so this
     * runs exactly once and no writer can be midway through the context. {@link #avStream} and
     * {@link #timeBase} belong to the context and are dangling the moment it is freed, so the
     * handles are nulled: a stray read afterwards then fails loudly with a NullPointerException
     * instead of quietly reading freed native memory.
     */
    private void freeOutputContext() {
        if (avFormatContext == null || avFormatContext.isNull())
            return;

        AVIOContext pb = avFormatContext.pb();
        if (pb != null && !pb.isNull())
            avformat.avio_close(pb);

        avformat.avformat_free_context(avFormatContext);

        avFormatContext = null;
        avStream = null;
        timeBase = null;
    }

    @Override
    public boolean isWriting() {
        return isWriting;
    }

    @Override
    public String getName() {
        return LISTENER_NAME;
    }

    public void setFileCloseCallback(Runnable closeCallback) {
        this.closeCallback = closeCallback;
    }
}