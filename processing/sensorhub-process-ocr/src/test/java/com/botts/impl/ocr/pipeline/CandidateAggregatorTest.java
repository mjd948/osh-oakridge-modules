package com.botts.impl.ocr.pipeline;

import com.botts.impl.ocr.api.OcrRead;
import org.junit.Test;
import org.sensorhub.impl.utils.rad.model.VehicleOcrResult;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CandidateAggregatorTest {

    private final BufferedImage frame = new BufferedImage(64, 64, BufferedImage.TYPE_3BYTE_BGR);

    private CandidateAggregator.FrameRead read(String text, float confidence) {
        return read(text, confidence, new Rectangle(0, 0, 10, 10));
    }

    private CandidateAggregator.FrameRead read(String text, float confidence, Rectangle bbox) {
        return new CandidateAggregator.FrameRead(
                new OcrRead(text, confidence, bbox),
                frame, "urn:osh:sensor:ffmpeg:cam1", Instant.now());
    }

    private OcrSettings settings() {
        OcrSettings settings = new OcrSettings();
        settings.containerMinConfidence = 0.5;
        settings.plateMinConfidence = 0.6;
        return settings;
    }

    @Test
    public void votesAcrossFramesByNormalizedValue() {
        List<CandidateAggregator.FrameRead> reads = List.of(
                read("CSQU 305438 3", 0.7f),
                read("csqu3054383", 0.9f),
                read("CSQU3054383", 0.8f));

        List<CandidateAggregator.Candidate> candidates = new CandidateAggregator(settings()).aggregate(reads);

        assertEquals(1, candidates.size());
        CandidateAggregator.Candidate candidate = candidates.get(0);
        assertEquals(VehicleOcrResult.ID_TYPE_CONTAINER, candidate.idType);
        assertEquals("CSQU3054383", candidate.normalizedValue);
        assertTrue(candidate.checksumValid);
        assertEquals(3, candidate.readCount);
        assertEquals(0.9f, candidate.confidence, 1e-6);
    }

    @Test
    public void keepsContainerAndPlateSeparately() {
        List<CandidateAggregator.FrameRead> reads = List.of(
                read("CSQU3054383", 0.8f),
                read("XYZ7890", 0.9f));

        List<CandidateAggregator.Candidate> candidates = new CandidateAggregator(settings()).aggregate(reads);

        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().anyMatch(c -> VehicleOcrResult.ID_TYPE_CONTAINER.equals(c.idType)));
        assertTrue(candidates.stream().anyMatch(c -> VehicleOcrResult.ID_TYPE_PLATE.equals(c.idType)));
    }

    @Test
    public void dropsPlateFragmentsOfContainerNumbers() {
        List<CandidateAggregator.FrameRead> reads = List.of(
                read("CSQU3054383", 0.8f),
                // the serial read on its own in another frame is not a plate
                read("3054383", 0.95f));

        List<CandidateAggregator.Candidate> candidates = new CandidateAggregator(settings()).aggregate(reads);

        assertEquals(1, candidates.size());
        assertEquals(VehicleOcrResult.ID_TYPE_CONTAINER, candidates.get(0).idType);
    }

    @Test
    public void checksumValidContainerSurvivesLowConfidence() {
        List<CandidateAggregator.Candidate> candidates = new CandidateAggregator(settings())
                .aggregate(List.of(read("CSQU3054383", 0.3f)));
        assertEquals(1, candidates.size());
        assertTrue(candidates.get(0).checksumValid);
    }

    @Test
    public void lowConfidenceUnvalidatedReadsAreDropped() {
        List<CandidateAggregator.FrameRead> reads = List.of(
                read("CSQU3054384", 0.3f),   // pattern-only container below floor
                read("AB1234", 0.4f));       // plate below floor

        assertTrue(new CandidateAggregator(settings()).aggregate(reads).isEmpty());
    }

    @Test
    public void checksumValidityOutweighsAModestConfidenceEdge() {
        List<CandidateAggregator.FrameRead> reads = List.of(
                read("CSQU3O54384", 0.8f),   // pattern-only: coerced serial, so not repairable
                read("TEMU1234565", 0.6f));  // checksum valid, lower confidence

        List<CandidateAggregator.Candidate> candidates = new CandidateAggregator(settings()).aggregate(reads);

        assertEquals(2, candidates.size());
        assertEquals("TEMU1234565", candidates.get(0).normalizedValue);
    }

    /**
     * A valid check digit is a weak discriminator — roughly one misread in
     * eleven validates by chance — so it is weighted, not absolute. Live on lane
     * NSDD4 the chance-valid TTBU3558532 at 0.687 outranked the correct read of
     * TGBU 355853 7 because of the old checksum-first sort.
     */
    @Test
    public void aMuchStrongerReadOutranksAChanceValidOne() {
        List<CandidateAggregator.FrameRead> reads = List.of(
                read("TTBU3558532", 0.687f),    // checksum valid, but the wrong owner code
                read("TGBU 355853 Z", 0.967f)); // check digit misread; repairs to ...537

        List<CandidateAggregator.Candidate> candidates = new CandidateAggregator(settings()).aggregate(reads);

        assertEquals("TGBU3558537", candidates.get(0).normalizedValue);
        assertTrue(candidates.get(0).checkDigitRepaired);
    }

    @Test
    public void capsCandidatesPerType() {
        List<CandidateAggregator.FrameRead> reads = new ArrayList<>();
        String[] plates = {"AAA1111", "BBB2222", "CCC3333", "DDD4444", "EEE5555"};
        for (int i = 0; i < plates.length; i++)
            reads.add(read(plates[i], 0.7f + i * 0.01f));

        List<CandidateAggregator.Candidate> candidates = new CandidateAggregator(settings()).aggregate(reads);
        assertEquals(CandidateAggregator.MAX_CANDIDATES_PER_TYPE, candidates.size());
    }

    @Test
    public void assemblesContainerFromSplitReadsOnSameLine() {
        // real containers box the check digit separately, so the detector often
        // yields "owner+serial" and the digit as two reads on one line
        List<CandidateAggregator.FrameRead> reads = List.of(
                read("CSQU 305438", 0.9f, new Rectangle(10, 50, 200, 30)),
                read("3", 0.8f, new Rectangle(230, 52, 20, 28)));

        List<CandidateAggregator.Candidate> candidates = new CandidateAggregator(settings()).aggregate(reads);

        assertEquals(1, candidates.size());
        CandidateAggregator.Candidate candidate = candidates.get(0);
        assertEquals(VehicleOcrResult.ID_TYPE_CONTAINER, candidate.idType);
        assertEquals("CSQU3054383", candidate.normalizedValue);
        assertTrue(candidate.checksumValid);
    }

    @Test
    public void doesNotConcatenateAcrossLines() {
        // vertically far apart, and neither half is a container number on its own
        List<CandidateAggregator.FrameRead> reads = List.of(
                read("CSQU 30543", 0.9f, new Rectangle(10, 50, 200, 30)),
                read("83", 0.8f, new Rectangle(10, 300, 20, 28)));

        List<CandidateAggregator.Candidate> candidates = new CandidateAggregator(settings()).aggregate(reads);
        assertTrue(candidates.stream().noneMatch(c -> "CSQU3054383".equals(c.normalizedValue)));
    }

    /**
     * Geometry taken from real footage (lane NSDD4, clip 2026-08-12_20-07-33):
     * the burned-in banner is read at x=274,y=2,w=364 on all seven sampled
     * frames of a 640x480 clip, while the container number moves from
     * (171,253) to (428,189) and is only legible on two of them.
     */
    private List<CandidateAggregator.FrameRead> bannerAndContainerFrames() {
        List<CandidateAggregator.FrameRead> reads = new ArrayList<>();
        Rectangle[] containerBoxes = {new Rectangle(171, 253, 115, 31), new Rectangle(428, 189, 80, 16)};
        for (int i = 0; i < 7; i++) {
            BufferedImage frame = new BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR);
            reads.add(new CandidateAggregator.FrameRead(
                    new OcrRead("BANZO260328", 0.96f, new Rectangle(274 + i % 2, 2 + i % 3, 364, 18)),
                    frame, "cam", Instant.now()));
            if (i < containerBoxes.length)
                reads.add(new CandidateAggregator.FrameRead(
                        new OcrRead("TGBU 355853 Z", 0.89f, containerBoxes[i]), frame, "cam", Instant.now()));
        }
        return reads;
    }

    @Test
    public void dropsTextThatBehavesLikeABurnedInCameraOverlay() {
        List<CandidateAggregator.Candidate> candidates =
                new CandidateAggregator(settings()).aggregate(bannerAndContainerFrames());

        assertTrue(candidates.stream().noneMatch(c -> "BANZ0260328".equals(c.normalizedValue)));
        assertEquals("TGBU3558537", candidates.get(0).normalizedValue);
    }

    @Test
    public void keepsAMovingContainerNumberEvenThoughItIsReadOnFewerFrames() {
        List<CandidateAggregator.Candidate> candidates =
                new CandidateAggregator(settings()).aggregate(bannerAndContainerFrames());

        CandidateAggregator.Candidate container = candidates.get(0);
        assertEquals(2, container.readCount);
        assertEquals(0, container.overlayLikelihood, 1e-9);
    }

    @Test
    public void ignoreBandDropsEdgeReadsAndIsInertWhenZero() {
        OcrSettings banded = settings();
        banded.ignoreTopFraction = 0.1;
        assertTrue(new CandidateAggregator(banded).aggregate(bannerAndContainerFrames()).stream()
                .noneMatch(c -> "BANZ0260328".equals(c.normalizedValue)));

        // a container read high in the frame survives with the band switched off
        BufferedImage frame = new BufferedImage(640, 480, BufferedImage.TYPE_3BYTE_BGR);
        CandidateAggregator.FrameRead high = new CandidateAggregator.FrameRead(
                new OcrRead("CSQU3054383", 0.9f, new Rectangle(10, 4, 120, 20)), frame, "cam", Instant.now());
        assertEquals(1, new CandidateAggregator(settings()).aggregate(List.of(high)).size());
        assertTrue(new CandidateAggregator(banded).aggregate(List.of(high)).isEmpty());
    }

    @Test
    public void respectsTypeToggles() {
        OcrSettings noPlates = settings();
        noPlates.readPlates = false;
        assertTrue(new CandidateAggregator(noPlates).aggregate(List.of(read("XYZ7890", 0.9f))).isEmpty());

        OcrSettings noContainers = settings();
        noContainers.readContainerIds = false;
        List<CandidateAggregator.Candidate> candidates =
                new CandidateAggregator(noContainers).aggregate(List.of(read("CSQU3054383", 0.9f)));
        // with container reading off, an 11-char read is not plate-shaped either
        assertTrue(candidates.isEmpty());
    }
}
