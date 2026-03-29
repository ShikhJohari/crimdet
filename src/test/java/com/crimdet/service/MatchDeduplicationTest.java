package com.crimdet.service;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the match cooldown/deduplication logic used in webcam live detection.
 * Same logic as LiveMonitorController.logMatchWithDeduplication.
 */
class MatchDeduplicationTest {

    private static final Duration COOLDOWN = Duration.ofSeconds(30);
    private final Map<Long, Instant> cooldowns = new ConcurrentHashMap<>();

    /** Returns true if the match should be logged (not cooled down). */
    private boolean shouldLog(long criminalId, Instant now) {
        Instant lastLogged = cooldowns.get(criminalId);
        if (lastLogged != null && Duration.between(lastLogged, now).compareTo(COOLDOWN) < 0) {
            return false;
        }
        cooldowns.put(criminalId, now);
        return true;
    }

    @BeforeEach
    void clear() {
        cooldowns.clear();
    }

    @Test
    void firstMatchIsAlwaysLogged() {
        assertTrue(shouldLog(1L, Instant.now()));
    }

    @Test
    void sameCriminalWithin30sIsNotReLogged() {
        Instant now = Instant.now();
        assertTrue(shouldLog(1L, now));
        assertFalse(shouldLog(1L, now.plusSeconds(10)));
        assertFalse(shouldLog(1L, now.plusSeconds(29)));
    }

    @Test
    void sameCriminalAfter30sIsLoggedAgain() {
        Instant now = Instant.now();
        assertTrue(shouldLog(1L, now));
        assertTrue(shouldLog(1L, now.plusSeconds(31)));
    }

    @Test
    void differentCriminalsLoggedIndependently() {
        Instant now = Instant.now();
        assertTrue(shouldLog(1L, now));
        assertTrue(shouldLog(2L, now));
        assertFalse(shouldLog(1L, now.plusSeconds(10)));
        assertTrue(shouldLog(2L, now.plusSeconds(31)));
    }
}
