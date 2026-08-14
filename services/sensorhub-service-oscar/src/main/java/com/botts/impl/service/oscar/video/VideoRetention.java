package com.botts.impl.service.oscar.video;

import com.botts.api.service.bucket.IBucketStore;
import org.sensorhub.api.ISensorHub;
import org.sensorhub.api.datastore.DataStoreException;
import org.sensorhub.impl.utils.rad.model.Occupancy;
import org.sensorhub.mpegts.MpegTsProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.time.temporal.TemporalAmount;

public class VideoRetention {

    private static final Logger logger = LoggerFactory.getLogger(VideoRetention.class);

    /** Anything smaller than this is an mp4 header with no usable video in it. */
    static final long MIN_VALID_DECIMATED_BYTES = 4096;

    final IBucketStore bucketStore;
    volatile boolean hasStarted = false;
    int frameCount;

    SlidingOccupancyQuery retentionQuery;

    public VideoRetention(ISensorHub hub, IBucketStore bucketStore, TemporalAmount queryPeriod, TemporalAmount retentionTimeOffset, int frameCount) {
        this.bucketStore = bucketStore;
        this.frameCount = frameCount;

        SlidingOccupancyQuery.QueryAction queryAction = frameCount > 0 ? this::decimateOccupancyVideo : this::deleteOccupancyVideo;

        retentionQuery = new SlidingOccupancyQuery(hub,
                queryPeriod,
                queryAction,
                retentionTimeOffset);
    }

    public synchronized void start() {
        if (hasStarted) {
            logger.warn("Video retention already started");
            return;
        }

        hasStarted = true;
        retentionQuery.start();
    }

    private boolean deleteOccupancyVideo(Occupancy occupancy) {
        for (String videoFile : occupancy.getVideoPaths()) {
            logger.info("deleting, {}", videoFile);
            if (!bucketStore.objectExists("", videoFile)) {
                logger.info("Video file {} does not exist or has already been deleted", videoFile);
                return false;
            } else {
                try {
                    bucketStore.deleteObject("", videoFile);
                } catch (DataStoreException e) {
                    logger.info("Failed to delete video file {}", videoFile, e);
                    return false;
                }
            }
        }
        return true;
    }

    private boolean decimateOccupancyVideo(Occupancy occupancy) {
        for (String videoFile : occupancy.getVideoPaths()) {
            logger.info("decimating, {}", occupancy.getVideoPaths().get(0));
            if (!bucketStore.objectExists("", videoFile)) {
                logger.info("Video file {} does not exist", videoFile);
                continue;
            }
            try {
                if (!decimate(bucketStore.getResourceURI("", videoFile))) {
                    logger.info("Video file was already decimated {}", videoFile);
                    return false;
                }
            } catch (DataStoreException e) {
                logger.warn("Failed to decimate video file {}", videoFile, e);
                continue;
            }
        }
        return true;
    }

    public synchronized void stop() {
        if (!hasStarted)
            return;

        hasStarted = false;
        retentionQuery.stop();
    }


    /**
     *
     * @param fileName Result of getResourceURI on an object from the bucket store
     * @return true if input was not already decimated (fps > 1)
     */
    public boolean decimate(String fileName) {

        String originalMp4 = fileName;
        String decimatedMp4 = fileName.substring(0, fileName.lastIndexOf('.')) + "_decimated.mp4";

        MpegTsProcessor videoInput = new MpegTsProcessor(originalMp4);
        VideoKeyframeDecimator videoOutput = null;
        boolean success = false;
        try {
            videoInput.setInjectExtradata(false);
            videoInput.openStream();
            videoInput.queryEmbeddedStreams();
            var stream = videoInput.getAvStream();
            if (stream == null || stream.avg_frame_rate() == null || stream.avg_frame_rate().num() < stream.avg_frame_rate().den()) {
                return false; // if fps < 1, assume this file has already been decimated and we lost track somehow
            }

            videoOutput = new VideoKeyframeDecimator(decimatedMp4, frameCount, stream);
            videoInput.addVideoDataBufferListener(videoOutput);

            // Only signal the demuxer to wind down. The input must not be closed from here:
            // this callback runs on the listener executor thread while STREAM-PROCESSOR is
            // still inside av_read_frame, and freeing the AVFormatContext underneath it
            // segfaults the JVM. The close belongs after join(), in the finally block.
            videoOutput.setFileCloseCallback(videoInput::stopProcessingStream);

            videoInput.processStream();

            try {
                videoInput.join();
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for video processing to finish. Writing output early, stopping thread.", e);
                videoInput.stopProcessingStream();
                Thread.currentThread().interrupt();
            }

            // A clip that reaches EOF before the keyframe quota never fires the close
            // callback, so the trailer would never be written and the output would be an
            // unplayable stub. Closing here is a no-op if the quota already closed it.
            videoOutput.closeFile();

            success = true;
        } catch (Throwable t) {
            logger.error("Failed to decimate video file {}; skipping", originalMp4, t);
            return false;
        } finally {
            // Safe here: the processing thread has been joined, or was never started.
            try {
                videoInput.stopProcessingStream();
                videoInput.closeStream();
            } catch (Throwable t) {
                logger.warn("Failed to close video input for {}", originalMp4, t);
            }
            if (!success) {
                var partial = Paths.get(decimatedMp4).toFile();
                if (partial.exists() && !partial.delete()) {
                    logger.warn("Failed to delete partial decimated file {}", decimatedMp4);
                }
            }
        }

        // TODO May be nice to rename objects through the bucket store
        var decimatedFile = Paths.get(decimatedMp4).toFile();
        var originalFile = Paths.get(originalMp4).toFile();

        // Never destroy the original for an output we cannot vouch for. A header-only mp4
        // (no trailer, no frames) is a few hundred bytes and is what previously replaced
        // thousands of clips.
        if (!decimatedFile.isFile() || decimatedFile.length() < MIN_VALID_DECIMATED_BYTES) {
            logger.warn("Decimated file {} is missing or too small ({} bytes); keeping original {}",
                    decimatedMp4, decimatedFile.isFile() ? decimatedFile.length() : 0, originalMp4);
            if (decimatedFile.exists() && !decimatedFile.delete())
                logger.warn("Failed to delete unusable decimated file {}", decimatedMp4);
            return false;
        }

        if (!originalFile.delete()) {
            logger.warn("Failed to delete original video file {}; leaving decimated copy at {}", originalMp4, decimatedMp4);
            return false;
        }
        if (!decimatedFile.renameTo(originalFile)) {
            logger.error("Failed to rename {} over {}", decimatedMp4, originalMp4);
            return false;
        }
        return true;
    }
}