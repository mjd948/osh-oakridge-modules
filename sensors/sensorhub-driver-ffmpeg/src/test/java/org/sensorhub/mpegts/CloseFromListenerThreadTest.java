package org.sensorhub.mpegts;

import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for the cross-thread use-after-free of the demuxer's AVFormatContext.
 * <p>
 * Before the fix, closing the stream from a listener thread while STREAM-PROCESSOR was
 * inside {@code av_read_frame} passed a freed (JavaCPP-zeroed) pointer to ffmpeg and
 * killed the JVM with SIGSEGV at fault address 0x4. The node did this on every video
 * retention pass and crashed about ten times a day.
 * <p>
 * The crash cannot be asserted in-process, so the scenario runs in a forked JVM and this
 * test checks that it exits cleanly and leaves no hs_err dump behind.
 */
public class CloseFromListenerThreadTest {

    private static final String SAMPLE = "/org/sensorhub/impl/sensor/ffmpeg/sample-stream.ts";
    private static final int ITERATIONS = 200;
    private static final int FRAMES_BEFORE_CLOSE = 5;   // matches frameRetentionCount in production
    private static final int TIMEOUT_MINUTES = 10;

    @Test
    public void closingFromListenerThreadDoesNotCrashTheJvm() throws Exception {

        Path workDir = Files.createTempDirectory("close-race-test");
        Path source = workDir.resolve("sample-stream.ts");
        try (InputStream in = getClass().getResourceAsStream(SAMPLE)) {
            assertTrue("missing test resource " + SAMPLE, in != null);
            Files.copy(in, source, StandardCopyOption.REPLACE_EXISTING);
        }

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(CloseFromListenerThreadHarness.class.getName());
        command.add(source.toString());
        command.add(Integer.toString(ITERATIONS));
        command.add(Integer.toString(FRAMES_BEFORE_CLOSE));

        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(workDir.resolve("harness.log").toFile())
                .start();

        assertTrue("harness did not finish within " + TIMEOUT_MINUTES + " minutes",
                process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES));

        String output = Files.readString(workDir.resolve("harness.log"));
        File[] dumps = workDir.toFile().listFiles((dir, name) -> name.startsWith("hs_err_pid"));

        assertEquals("harness JVM died — likely SIGSEGV in ffmpeg native code.\n" + output,
                0, process.exitValue());
        assertTrue("harness produced a JVM crash dump:\n" + output,
                dumps == null || dumps.length == 0);
        assertTrue("harness did not run to completion:\n" + output,
                output.contains("all " + ITERATIONS + " iterations survived"));
    }
}
