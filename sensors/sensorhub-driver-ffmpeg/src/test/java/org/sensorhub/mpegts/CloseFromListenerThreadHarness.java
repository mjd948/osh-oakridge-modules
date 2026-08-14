package org.sensorhub.mpegts;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reproduces the crash pattern that took the OSCAR node down roughly ten times a day:
 * a {@link DataBufferListener} that tears the demuxer down from the listener executor
 * thread while STREAM-PROCESSOR is still inside {@code av_read_frame}.
 * <p>
 * This runs as a {@code main} rather than a plain JUnit test because the failure mode is
 * a SIGSEGV, which kills the JVM outright — an in-process assertion never gets to run.
 * {@link CloseFromListenerThreadTest} forks this and checks the exit code.
 * <p>
 * Usage: {@code CloseFromListenerThreadHarness <source> <iterations> <framesBeforeClose>}
 */
public class CloseFromListenerThreadHarness {

    public static void main(String[] args) {
        String source = args[0];
        int iterations = Integer.parseInt(args[1]);
        int framesBeforeClose = Integer.parseInt(args[2]);

        for (int i = 0; i < iterations; i++) {
            MpegTsProcessor processor = new MpegTsProcessor(source);
            // Mirrors VideoRetention's inputClosed guard: whoever closes first wins, so the
            // teardown races the read loop rather than double-freeing.
            AtomicBoolean closed = new AtomicBoolean(false);
            try {
                processor.setInjectExtradata(false);
                if (!processor.openStream()) {
                    System.err.println("Could not open " + source);
                    System.exit(2);
                }
                processor.queryEmbeddedStreams();
                if (!processor.hasVideoStream()) {
                    System.err.println("No video stream in " + source);
                    System.exit(2);
                }

                processor.addVideoDataBufferListener(new ClosingListener(processor, framesBeforeClose, closed));
                processor.processStream();
                processor.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.exit(3);
            } finally {
                if (closed.compareAndSet(false, true)) {
                    try {
                        processor.stopProcessingStream();
                        processor.closeStream();
                    } catch (Throwable t) {
                        // A failure to close is not what this harness is testing.
                        t.printStackTrace();
                    }
                }
            }

            if (i % 25 == 0)
                System.out.println("iteration " + i + " survived");
        }

        System.out.println("all " + iterations + " iterations survived");
    }

    /** Calls closeStream() from the listener executor thread, exactly as VideoRetention used to. */
    private static class ClosingListener implements DataBufferListener {

        private final MpegTsProcessor processor;
        private final int framesBeforeClose;
        private final AtomicBoolean closed;
        private int seen = 0;
        private volatile boolean writing = true;

        ClosingListener(MpegTsProcessor processor, int framesBeforeClose, AtomicBoolean closed) {
            this.processor = processor;
            this.framesBeforeClose = framesBeforeClose;
            this.closed = closed;
        }

        @Override
        public void onDataBuffer(DataBufferRecord record) {
            if (!writing)
                return;
            if (++seen < framesBeforeClose)
                return;

            writing = false;
            // The bug: freeing the AVFormatContext from this thread while the demuxing
            // thread is still reading it.
            if (closed.compareAndSet(false, true)) {
                processor.stopProcessingStream();
                processor.closeStream();
            }
        }

        @Override
        public boolean isWriting() {
            return writing;
        }

        @Override
        public String getName() {
            return "CLOSE-RACE";
        }
    }
}
