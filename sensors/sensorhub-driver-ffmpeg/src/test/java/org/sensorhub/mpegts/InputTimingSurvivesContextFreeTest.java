package org.sensorhub.mpegts;

import org.bytedeco.ffmpeg.avutil.AVRational;
import org.junit.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for the dangling input timing pointers that FileOutput used to cache.
 * <p>
 * {@code AVStream.time_base()} and {@code avg_frame_rate()} are {@code @ByRef} accessors:
 * they return pointers into the demuxer's AVFormatContext, not values. FileOutput cached
 * them when it opened a clip and dereferenced them per packet on the listener thread, while
 * {@code reopenStream()} freed and replaced that context on every RTSP reconnect. The reads
 * that followed were a use-after-free.
 * <p>
 * The crash itself cannot be asserted — a freed heap read usually returns plausible bytes,
 * which is why the fault was rare and random rather than immediate. So this test pins the
 * property that makes the crash impossible instead: what the processor hands out is a copy,
 * at an address outside the context, still readable once the context is gone.
 */
public class InputTimingSurvivesContextFreeTest {

    private static final String SAMPLE = "/org/sensorhub/impl/sensor/ffmpeg/sample-stream.ts";

    @Test
    public void inputTimingIsCopiedOutOfTheFormatContext() throws Exception {

        Path workDir = Files.createTempDirectory("input-timing-test");
        Path source = workDir.resolve("sample-stream.ts");
        try (InputStream in = getClass().getResourceAsStream(SAMPLE)) {
            assertTrue("missing test resource " + SAMPLE, in != null);
            Files.copy(in, source, StandardCopyOption.REPLACE_EXISTING);
        }

        MpegTsProcessor processor = new MpegTsProcessor(source.toString());
        processor.setInjectExtradata(false);
        assertTrue("could not open " + source, processor.openStream());
        processor.queryEmbeddedStreams();
        assertTrue("no video stream in " + source, processor.hasVideoStream());

        AVRational liveTimeBase = processor.getAvStream().time_base();
        AVRational timeBase = processor.getInputTimeBase();

        assertNotNull("the stream declares a time base", timeBase);
        assertEquals(liveTimeBase.num(), timeBase.num());
        assertEquals(liveTimeBase.den(), timeBase.den());
        assertNotEquals("getInputTimeBase() handed back a pointer into the format context",
                liveTimeBase.address(), timeBase.address());

        // A live source often declares no average frame rate. That has to read as absent so
        // callers fall back to a default, rather than as a 0/0 that divides badly later on.
        AVRational liveFrameRate = processor.getAvStream().avg_frame_rate();
        AVRational frameRate = processor.getInputFrameRate();
        if (liveFrameRate.num() > 0 && liveFrameRate.den() > 0) {
            assertNotNull(frameRate);
            assertEquals(liveFrameRate.num(), frameRate.num());
            assertEquals(liveFrameRate.den(), frameRate.den());
            assertNotEquals("getInputFrameRate() handed back a pointer into the format context",
                    liveFrameRate.address(), frameRate.address());
        } else {
            assertNull("an undeclared frame rate must read as absent, not as 0/0", frameRate);
        }

        int num = timeBase.num();
        int den = timeBase.den();

        // Reconnecting frees the context these values came from. FileOutput keeps using them
        // across that boundary, so they have to outlive it.
        processor.closeStream();

        assertEquals("time base did not survive the context being freed", num, timeBase.num());
        assertEquals("time base did not survive the context being freed", den, timeBase.den());
        assertNull("no stream is open, so there is no timing to report", processor.getInputTimeBase());
        assertNull("no stream is open, so there is no timing to report", processor.getInputFrameRate());
    }
}
