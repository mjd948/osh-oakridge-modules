package com.botts.impl.ocr.pipeline;

import com.botts.impl.ocr.api.OcrRead;
import com.botts.impl.ocr.text.Iso6346;
import com.botts.impl.ocr.text.PlateHeuristics;
import org.sensorhub.impl.utils.rad.model.VehicleOcrResult;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns raw per-frame OCR reads into deduplicated vehicle-ID candidates:
 * classify each read as a container number or plate, vote across frames and
 * cameras by normalized value, keep the best-confidence frame as evidence.
 */
public class CandidateAggregator {

    public static final int MAX_CANDIDATES_PER_TYPE = 3;

    /** One OCR read in the context of the frame it came from. */
    public static class FrameRead {
        public final OcrRead read;
        public final BufferedImage frameImage;
        public final String cameraUid;
        public final Instant frameTime;

        public FrameRead(OcrRead read, BufferedImage frameImage, String cameraUid, Instant frameTime) {
            this.read = read;
            this.frameImage = frameImage;
            this.cameraUid = cameraUid;
            this.frameTime = frameTime;
        }
    }

    /**
     * A burned-in camera overlay is text that never moves, sits against a frame
     * edge, runs most of the way across it and is therefore read on virtually
     * every sampled frame. Real painted markings on a vehicle trip none of these
     * while the vehicle is driving through the lane.
     */
    static final int STATIC_BOX_TOLERANCE_PX = 6;
    static final double EDGE_BAND_FRACTION = 0.12;
    static final double WIDE_BOX_FRACTION = 0.4;
    /** Overlay likelihood at or above which a candidate is not published at all. */
    static final double OVERLAY_DROP_LIKELIHOOD = 0.6;

    /** Weight of a valid ISO 6346 check digit, in units of recognizer confidence. */
    static final double CHECKSUM_WEIGHT = 0.35;
    /** Frame agreement is only a tie-break: on real footage an overlay is read on
     *  every frame while a container number is legible on one or two. */
    static final double READ_COUNT_WEIGHT = 0.05;
    static final int READ_COUNT_SATURATION = 4;
    static final double OVERLAY_WEIGHT = 0.6;

    /** An aggregated candidate plus the evidence frame/box it was best read from. */
    public static class Candidate {
        public final String idType;
        public String value;
        public String normalizedValue;
        public boolean checksumValid;
        public float confidence;
        public int readCount;
        public String cameraUid;
        public Instant frameTime;
        public BufferedImage evidenceFrame;
        public Rectangle evidenceBox;
        /** True when every read of this value had its check digit recomputed. */
        public boolean checkDigitRepaired;
        /** 0..1: how much this candidate behaves like a burned-in camera overlay. */
        public double overlayLikelihood;
        public double score;

        private Rectangle firstBox;
        private int staticReads;
        private int edgeBandReads;
        private int wideReads;

        Candidate(String idType) {
            this.idType = idType;
        }

        void noteGeometry(Rectangle box, BufferedImage frame) {
            if (firstBox == null)
                firstBox = box;
            else if (Math.abs(box.x - firstBox.x) <= STATIC_BOX_TOLERANCE_PX
                    && Math.abs(box.y - firstBox.y) <= STATIC_BOX_TOLERANCE_PX
                    && Math.abs(box.width - firstBox.width) <= STATIC_BOX_TOLERANCE_PX)
                staticReads++;

            if (frame == null || frame.getWidth() == 0 || frame.getHeight() == 0)
                return;
            double centreY = (box.y + box.height / 2.0) / frame.getHeight();
            if (centreY <= EDGE_BAND_FRACTION || centreY >= 1 - EDGE_BAND_FRACTION)
                edgeBandReads++;
            if (box.width / (double) frame.getWidth() >= WIDE_BOX_FRACTION)
                wideReads++;
        }

        /** All four overlay cues must agree, so a container number scores ~0 here. */
        void scoreOverlay(int totalFrames) {
            if (readCount < 2 || totalFrames < 2) {
                overlayLikelihood = 0;
                return;
            }
            double stable = staticReads / (double) (readCount - 1);
            double coverage = Math.min(1, readCount / (double) totalFrames);
            overlayLikelihood = stable
                    * (edgeBandReads / (double) readCount)
                    * (wideReads / (double) readCount)
                    * coverage;
        }
    }

    private final OcrSettings settings;

    public CandidateAggregator(OcrSettings settings) {
        this.settings = settings;
    }

    public List<Candidate> aggregate(List<FrameRead> allFrameReads) {
        List<FrameRead> frameReads = new ArrayList<>();
        for (FrameRead frameRead : allFrameReads)
            if (!inIgnoredBand(frameRead))
                frameReads.add(frameRead);

        Map<String, Candidate> byKey = new LinkedHashMap<>();

        for (FrameRead frameRead : frameReads) {
            String normalized = Iso6346.normalize(frameRead.read.text);
            if (normalized.isEmpty())
                continue;

            Iso6346.Match container = settings.readContainerIds ? Iso6346.scan(normalized) : null;
            if (container != null)
                merge(byKey, frameRead, VehicleOcrResult.ID_TYPE_CONTAINER,
                        container.value, container.checksumValid, container.checkDigitRepaired);

            if (settings.readPlates && container == null && PlateHeuristics.looksLikePlate(normalized))
                merge(byKey, frameRead, VehicleOcrResult.ID_TYPE_PLATE, normalized, false, false);
        }

        if (settings.readContainerIds)
            scanLineConcatenations(byKey, frameReads);

        List<Candidate> candidates = new ArrayList<>(byKey.values());
        // a "plate" that is just a fragment of a read container number is OCR noise
        candidates.removeIf(candidate ->
                VehicleOcrResult.ID_TYPE_PLATE.equals(candidate.idType)
                        && candidates.stream().anyMatch(other ->
                        VehicleOcrResult.ID_TYPE_CONTAINER.equals(other.idType)
                                && other.normalizedValue.contains(candidate.normalizedValue)));

        candidates.removeIf(candidate -> {
            double floor = VehicleOcrResult.ID_TYPE_CONTAINER.equals(candidate.idType)
                    ? settings.containerMinConfidence
                    : settings.plateMinConfidence;
            // a recomputed check digit vouches for nothing the recognizer read,
            // so it does not earn the waiver a read-and-validated one does
            boolean vouchedFor = candidate.checksumValid && !candidate.checkDigitRepaired;
            return !vouchedFor && candidate.confidence < floor;
        });

        int totalFrames = countFrames(frameReads);
        for (Candidate candidate : candidates) {
            candidate.scoreOverlay(totalFrames);
            candidate.score = candidate.confidence
                    + (candidate.checksumValid ? CHECKSUM_WEIGHT : 0)
                    + READ_COUNT_WEIGHT
                            * Math.min(candidate.readCount, READ_COUNT_SATURATION) / READ_COUNT_SATURATION
                    - OVERLAY_WEIGHT * candidate.overlayLikelihood;
        }

        // Publishing an overlay can only mislead the operator, and dropping it
        // here keeps the published order the same one the viewer recomputes from
        // the fields on the record.
        candidates.removeIf(candidate -> candidate.overlayLikelihood >= OVERLAY_DROP_LIKELIHOOD);

        candidates.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed());

        List<Candidate> capped = new ArrayList<>();
        int containers = 0, plates = 0;
        for (Candidate candidate : candidates) {
            if (VehicleOcrResult.ID_TYPE_CONTAINER.equals(candidate.idType)) {
                if (containers++ < MAX_CANDIDATES_PER_TYPE)
                    capped.add(candidate);
            } else {
                if (plates++ < MAX_CANDIDATES_PER_TYPE)
                    capped.add(candidate);
            }
        }
        return capped;
    }

    /**
     * A container number often detects as several boxes (the check digit is
     * printed in its own box on real containers), so also scan the x-ordered
     * concatenation of the reads on each text line of each frame.
     */
    private void scanLineConcatenations(Map<String, Candidate> byKey, List<FrameRead> frameReads) {
        Map<BufferedImage, List<FrameRead>> byFrame = new LinkedHashMap<>();
        for (FrameRead frameRead : frameReads)
            byFrame.computeIfAbsent(frameRead.frameImage, key -> new ArrayList<>()).add(frameRead);

        for (List<FrameRead> frame : byFrame.values()) {
            for (List<FrameRead> line : groupIntoLines(frame)) {
                if (line.size() < 2)
                    continue;
                // fallback only: a read that already validated on its own was
                // counted in the per-read pass above
                boolean alreadyValid = line.stream().anyMatch(read -> {
                    Iso6346.Match match = Iso6346.scan(Iso6346.normalize(read.read.text));
                    // a repaired read still leaves the printed check digit unread,
                    // so let the concatenation try to recover it for real
                    return match != null && match.checksumValid && !match.checkDigitRepaired;
                });
                if (alreadyValid)
                    continue;
                line.sort(Comparator.comparingInt(read -> read.read.bbox.x));

                String joinedRaw = line.stream().map(read -> read.read.text)
                        .collect(java.util.stream.Collectors.joining(" "));
                Iso6346.Match match = Iso6346.scan(Iso6346.normalize(joinedRaw));
                if (match == null)
                    continue;

                float confidence = (float) line.stream()
                        .mapToDouble(read -> read.read.confidence).average().orElse(0);
                Rectangle union = new Rectangle(line.get(0).read.bbox);
                for (FrameRead read : line)
                    union = union.union(read.read.bbox);

                FrameRead synthetic = new FrameRead(new OcrRead(joinedRaw, confidence, union),
                        line.get(0).frameImage, line.get(0).cameraUid, line.get(0).frameTime);
                merge(byKey, synthetic, VehicleOcrResult.ID_TYPE_CONTAINER,
                        match.value, match.checksumValid, match.checkDigitRepaired);
            }
        }
    }

    private static List<List<FrameRead>> groupIntoLines(List<FrameRead> frame) {
        List<List<FrameRead>> lines = new ArrayList<>();
        for (FrameRead read : frame) {
            List<FrameRead> target = null;
            for (List<FrameRead> line : lines) {
                if (verticallyOverlaps(line.get(0).read.bbox, read.read.bbox)) {
                    target = line;
                    break;
                }
            }
            if (target != null)
                target.add(read);
            else {
                List<FrameRead> line = new ArrayList<>();
                line.add(read);
                lines.add(line);
            }
        }
        return lines;
    }

    private static boolean verticallyOverlaps(Rectangle a, Rectangle b) {
        int overlap = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y);
        return overlap >= Math.min(a.height, b.height) * 0.5;
    }

    /** True when the read sits in a frame edge band the lane has configured away. */
    private boolean inIgnoredBand(FrameRead frameRead) {
        if (settings.ignoreTopFraction <= 0 && settings.ignoreBottomFraction <= 0)
            return false;
        BufferedImage frame = frameRead.frameImage;
        if (frame == null || frame.getHeight() == 0)
            return false;
        Rectangle box = frameRead.read.bbox;
        double centreY = (box.y + box.height / 2.0) / frame.getHeight();
        return centreY <= settings.ignoreTopFraction
                || centreY >= 1 - settings.ignoreBottomFraction;
    }

    private static int countFrames(List<FrameRead> frameReads) {
        Map<BufferedImage, Boolean> frames = new LinkedHashMap<>();
        for (FrameRead frameRead : frameReads)
            frames.put(frameRead.frameImage, Boolean.TRUE);
        return frames.size();
    }

    private void merge(Map<String, Candidate> byKey, FrameRead frameRead, String idType,
                       String normalizedValue, boolean checksumValid, boolean checkDigitRepaired) {
        Candidate candidate = byKey.computeIfAbsent(idType + ":" + normalizedValue,
                key -> new Candidate(idType));
        boolean first = candidate.readCount == 0;
        candidate.readCount++;
        candidate.checksumValid |= checksumValid;
        // one read that carried its own valid check digit settles it for the value
        candidate.checkDigitRepaired = first
                ? checkDigitRepaired
                : candidate.checkDigitRepaired && checkDigitRepaired;
        candidate.noteGeometry(frameRead.read.bbox, frameRead.frameImage);

        float confidence = frameRead.read.confidence;
        if (candidate.value == null || confidence > candidate.confidence) {
            candidate.value = frameRead.read.text;
            candidate.normalizedValue = normalizedValue;
            candidate.confidence = confidence;
            candidate.cameraUid = frameRead.cameraUid;
            candidate.frameTime = frameRead.frameTime;
            candidate.evidenceFrame = frameRead.frameImage;
            candidate.evidenceBox = frameRead.read.bbox;
        }
    }
}
