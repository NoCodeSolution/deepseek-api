package com.nocodesolution.deepseek_free_api.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Эталонные тесты ChicoryPowSolver (AoT-режим Chicory).
 * wasm берётся из test classpath — сеть не используется.
 */
class ChicoryPowSolverTest {

    private static final String CHALLENGE_1 =
            "97ab9ed8be6fc75a4cbf034a5651efcf143679dc311757f9e63fede8397fc908";
    private static final String CHALLENGE_2 =
            "493b25842f2808cd568afa97f94d6573b57c2d194fdc9634f290e212c032eea2";

    private static ChicoryPowSolver solver;

    @BeforeAll
    static void setUp() throws Exception {
        byte[] wasm;
        try (InputStream in =
                ChicoryPowSolverTest.class.getResourceAsStream("/sha3_wasm_bg.wasm")) {
            assertNotNull(in, "test resource /sha3_wasm_bg.wasm missing");
            wasm = in.readAllBytes();
        }
        solver = ChicoryPowSolver.fromBytes(wasm);
    }

    private static Map<String, Object> challenge(
            String algorithm, String challenge, String salt, Object difficulty, Object expireAt) {
        Map<String, Object> map = new HashMap<>();
        map.put("algorithm", algorithm);
        map.put("challenge", challenge);
        map.put("salt", salt);
        map.put("difficulty", difficulty);
        if (expireAt != null) {
            map.put("expire_at", expireAt);
        }
        return map;
    }

    // ─── эталонные векторы (записаны с реального прогона WASM-модуля) ───

    @Test
    void vector1() {
        long answer = solver.solve(challenge(
                "DeepSeekHashV1", CHALLENGE_1, "b62ae224bef425477d38", 144000, 1791487208088L));
        assertEquals(69992L, answer);
    }

    @Test
    void vector2() {
        long answer = solver.solve(challenge(
                "DeepSeekHashV1", CHALLENGE_2, "fe8d3a92cb44f26be1e1", 144000, 1791487208678L));
        assertEquals(44911L, answer);
    }

    // ─── негативы ───

    @Test
    void oddLengthChallengeIsPowExceptionNotCrash() {
        // нечётная длина hex → wasm status 0 → PowException
        PowException ex = assertThrows(PowException.class, () -> solver.solve(challenge(
                "DeepSeekHashV1", "abc", "123", 1, 1893456000L)));
        assertEquals("PoW solver did not return a valid integer answer.", ex.getMessage());
    }

    @Test
    void unsupportedAlgorithm() {
        PowException ex = assertThrows(PowException.class, () -> solver.solve(challenge(
                "Other", "aa", "1", 1, 1L)));
        assertEquals("Unsupported PoW algorithm: Other", ex.getMessage());
    }

    @Test
    void missingExpireAt() {
        PowException ex = assertThrows(PowException.class, () -> solver.solve(challenge(
                "DeepSeekHashV1", "aa", "1", 1, null)));
        assertEquals("PoW challenge is missing expire_at.", ex.getMessage());
    }

    @Test
    void missingDifficulty() {
        Map<String, Object> map = challenge("DeepSeekHashV1", "aa", "1", null, 1L);
        map.remove("difficulty");
        PowException ex = assertThrows(PowException.class, () -> solver.solve(map));
        assertEquals("PoW challenge is missing difficulty.", ex.getMessage());
    }

    @Test
    void stringDifficultyRejected() {
        PowException ex = assertThrows(PowException.class, () -> solver.solve(challenge(
                "DeepSeekHashV1", "aa", "1", "5", 1L)));
        assertEquals("PoW challenge is missing difficulty.", ex.getMessage());
    }

    // ─── регрессии ревью ───

    @Test
    void largeAnswerNotTruncated() {
        // регрессия: answer.intValue() обрезал ответы > 2^31; интерфейс — long
        DeepSeekHashGlue glue = mock(DeepSeekHashGlue.class);
        when(glue.calculateHash(anyString(), anyString(), anyDouble(), anyLong()))
                .thenReturn(3_000_000_000.0);
        ChicoryPowSolver bigAnswerSolver = ChicoryPowSolver.withGlue(glue);

        long answer = bigAnswerSolver.solve(challenge("DeepSeekHashV1", "aa", "1", 1, 1L));

        assertEquals(3_000_000_000L, answer);
    }

    @Test
    void parallelSolvesShareSingleInstance() throws Exception {
        // один общий wasm-инстанс под synchronized: 4 потока решают эталонные
        // векторы одновременно, все ответы должны быть верны
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<long[]>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                start.await();
                long a1 = solver.solve(challenge(
                        "DeepSeekHashV1", CHALLENGE_1, "b62ae224bef425477d38", 144000, 1791487208088L));
                long a2 = solver.solve(challenge(
                        "DeepSeekHashV1", CHALLENGE_2, "fe8d3a92cb44f26be1e1", 144000, 1791487208678L));
                return new long[] {a1, a2};
            });
        }
        var executor = Executors.newFixedThreadPool(threads);
        try {
            var futures = tasks.stream().map(executor::submit).toList();
            start.countDown();
            for (var future : futures) {
                long[] answers = future.get(30, TimeUnit.SECONDS);
                assertEquals(69992L, answers[0]);
                assertEquals(44911L, answers[1]);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void stringFieldsRejected() {
        // строковый expire_at не принимается
        PowException ex = assertThrows(PowException.class, () -> solver.solve(challenge(
                "DeepSeekHashV1", "abc", "123", "5", "1893456000")));
        assertEquals("PoW challenge is missing expire_at.", ex.getMessage());
    }

    // ─── защита от случайного интерпретаторного режима ───

    @Test
    void singleSolveIsFast() {
        long start = System.nanoTime();
        long answer = solver.solve(challenge(
                "DeepSeekHashV1", CHALLENGE_1, "b62ae224bef425477d38", 144000, 1791487208088L));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(69992L, answer);
        assertTrue(elapsedMs < 10_000,
                "solve took " + elapsedMs + " ms — interpreter mode instead of AoT?");
    }
}
