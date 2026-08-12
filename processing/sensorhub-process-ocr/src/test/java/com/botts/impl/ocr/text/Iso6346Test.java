package com.botts.impl.ocr.text;

import org.junit.Test;

import static org.junit.Assert.*;

public class Iso6346Test {

    @Test
    public void validatesKnownContainerNumbers() {
        assertTrue(Iso6346.isValid("CSQU3054383"));
        assertTrue(Iso6346.isValid("TEMU1234565"));
        // sum mod 11 == 10 maps to check digit 0
        assertTrue(Iso6346.isValid("MSKU0000500"));
    }

    @Test
    public void rejectsWrongCheckDigit() {
        assertFalse(Iso6346.isValid("CSQU3054384"));
        assertFalse(Iso6346.isValid("TEMU1234560"));
    }

    @Test
    public void rejectsWrongShape() {
        assertFalse(Iso6346.isValid("CSQ3054383"));      // too short
        assertFalse(Iso6346.isValid("CSQA3054383"));     // category must be U/J/Z
        assertFalse(Iso6346.isValid("12345678901"));     // no owner code
        assertFalse(Iso6346.isValid(null));
    }

    @Test
    public void checkDigitUsesGappedLetterTable() {
        // letter values skip multiples of 11: L=23 (not 22), V=34 (not 33)
        // LLLU0000000: 23*(1+2+4) + 32*8 + 0... = 161+256 = 417; 417 % 11 = 10 -> digit 0
        assertEquals(0, Iso6346.checkDigit("LLLU000000"));
        // VVVU0000000: 34*7 + 256 = 494; 494 % 11 = 10 -> 0... compute: 494/11=44.9, 44*11=484, rem 10 -> 0
        assertEquals(0, Iso6346.checkDigit("VVVU000000"));
    }

    @Test
    public void normalizeStripsSeparatorsAndUppercases() {
        assertEquals("CSQU3054383", Iso6346.normalize("csqu 305438-3"));
        assertEquals("ABC1234", Iso6346.normalize(" abc·12_34 "));
        assertEquals("", Iso6346.normalize(null));
    }

    @Test
    public void coercesOcrConfusionsByPosition() {
        // letter O misread in a digit position and digit 0 misread in a letter position
        assertEquals("CSQU3054383", Iso6346.coerceByPosition("CSQU3O54383"));
        assertEquals("COQU3054383", Iso6346.coerceByPosition("C0QU3054383"));
        assertTrue(Iso6346.isValid(Iso6346.coerceByPosition("CSQU3O54383")));
    }

    @Test
    public void scanFindsContainerInsideLongerString() {
        Iso6346.Match match = Iso6346.scan(Iso6346.normalize("xx CSQU 305438 3 yy"));
        assertNotNull(match);
        assertEquals("CSQU3054383", match.value);
        assertTrue(match.checksumValid);
    }

    @Test
    public void scanPrefersChecksumValidWindow() {
        // an invalid-looking prefix window should not shadow the valid one
        Iso6346.Match match = Iso6346.scan("AAAU1111111CSQU3054383");
        assertNotNull(match);
        assertTrue(match.checksumValid);
        assertEquals("CSQU3054383", match.value);
    }

    @Test
    public void scanReportsPatternOnlyMatchWhenNothingValidates() {
        Iso6346.Match match = Iso6346.scan("CSQU3054384");
        assertNotNull(match);
        assertFalse(match.checksumValid);
        assertEquals("CSQU3054384", match.value);
    }

    @Test
    public void scanReturnsNullWhenNothingMatches() {
        assertNull(Iso6346.scan("HELLO"));
        assertNull(Iso6346.scan(""));
        assertNull(Iso6346.scan(null));
    }

    @Test
    public void stripsBurnedInTimestamps() {
        assertEquals("L102 REAR CAM    ", Iso6346.stripTimestamps("L102 REAR CAM 2026-03-28 11:56:32"));
        assertEquals(" ", Iso6346.stripTimestamps("2026/03/28"));
        assertEquals(" ", Iso6346.stripTimestamps("28-03-2026"));
        assertEquals("", Iso6346.stripTimestamps(null));
        // a container number's own separators must survive
        assertEquals("csqu 305438-3", Iso6346.stripTimestamps("csqu 305438-3"));
    }

    /**
     * Regression: the Marshall lane camera's OSD banner was read on 8/8 sampled
     * frames of every clip and surfaced as container candidate CAMZ0260328 at
     * 0.96 confidence, crowding a real candidate out of the operator's
     * suggestions (observed live 2026-08-12 on datastream 0jag2).
     */
    @Test
    public void doesNotReadCameraOsdBannerAsContainer() {
        assertNull(Iso6346.scan(Iso6346.normalize("L102 REAR CAM 2026-03-28 11:56:32")));
        assertNull(Iso6346.scan(Iso6346.normalize("L102REARCAM 2026-03-2811:56:32")));
    }

    @Test
    public void rejectsPatternOnlyWindowWhoseCategoryWasCoercedFromADigit() {
        // same banner with the separators already dropped by the OCR engine, so
        // the timestamp strip cannot see it: CAM + 2026032 + 8 -> CAMZ0260328
        assertNull(Iso6346.scan("CAM20260328115632"));
        // a genuine category letter still yields a pattern-only candidate
        Iso6346.Match match = Iso6346.scan("CSQU3054384");
        assertNotNull(match);
        assertFalse(match.checksumValid);
    }

    @Test
    public void stillReadsRealContainersAroundTheseGuards() {
        // the container actually on the test footage, check digit valid
        Iso6346.Match match = Iso6346.scan(Iso6346.normalize("tex TGBU 355853 7 22G1"));
        assertNotNull(match);
        assertEquals("TGBU3558537", match.value);
        assertTrue(match.checksumValid);
    }
}
