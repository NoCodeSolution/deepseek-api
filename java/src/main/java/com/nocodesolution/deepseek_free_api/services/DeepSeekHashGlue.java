package com.nocodesolution.deepseek_free_api.services;

import com.dylibso.chicory.experimental.aot.AotMachine;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.Parser;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Java-порт glue-класса _DeepSeekHash из pow_solver.py.
 * Повторяет wbindgen-конвенции 1:1: __wbindgen_add_to_stack_pointer(-16),
 * __wbindgen_export_0 = malloc, __wbindgen_export_1 = realloc,
 * wasm_solve(retptr, ptr0, len0, ptr1, len1, difficulty:f64).
 *
 * <p>WASM исполняется в AoT-режиме Chicory ({@link AotMachine}) — интерпретатор
 * в ~200 раз медленнее Python. Instance thread-safe НЕ является, а ThreadLocal
 * на виртуальных трейдах вырождается в instance-per-request (каждый запрос —
 * новый трейд → новая AoT-компиляция модуля на каждый completion). Поэтому
 * один общий Instance создаётся один раз (вместе с AoT-трансляцией), а все
 * вызовы calculateHash сериализуются synchronized: решение занимает ~130 мс,
 * конкуренция за PoW несущественна.
 */
public final class DeepSeekHashGlue {

    private final Exports exports;

    public DeepSeekHashGlue(byte[] wasmBytes) {
        var module = Parser.parse(Objects.requireNonNull(wasmBytes, "wasmBytes"));
        Instance instance = Instance.builder(module).withMachineFactory(AotMachine::new).build();
        this.exports = new Exports(instance);
    }

    /**
     * @return значение f64 из wasm, либо {@code null}, если status == 0 (ответа нет)
     */
    public synchronized Double calculateHash(String challenge, String salt, double difficulty, long expireAt) {
        Exports ex = exports;
        String prefix = salt + "_" + expireAt + "_";
        long retptr = ex.addToStackPointer.apply(-16)[0];
        try {
            long[] enc0 = encodeString(ex, challenge);
            long[] enc1 = encodeString(ex, prefix);
            ex.wasmSolve.apply(
                    retptr,
                    enc0[0], enc0[1],
                    enc1[0], enc1[1],
                    Double.doubleToRawLongBits(difficulty));
            int status = ex.memory.readInt((int) retptr);
            double value = Double.longBitsToDouble(ex.memory.readLong((int) retptr + 8));
            return status == 0 ? null : value;
        } finally {
            ex.addToStackPointer.apply(16);
        }
    }

    /** Порт _encode_string: ascii-префикс через malloc, не-ascii через realloc (+3 байта/символ). */
    private static long[] encodeString(Exports ex, String text) {
        int strLength = text.length();
        long ptr = ex.malloc.apply(strLength, 1)[0] & 0xFFFFFFFFL;

        int asciiLength = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                break;
            }
            asciiLength++;
        }
        if (asciiLength > 0) {
            byte[] ascii = text.substring(0, asciiLength).getBytes(StandardCharsets.US_ASCII);
            ex.memory.write((int) ptr, ascii);
        }

        if (asciiLength != strLength) {
            String rest = text.substring(asciiLength);
            int grown = asciiLength + rest.length() * 3;
            ptr = ex.realloc.apply(ptr, strLength, grown, 1)[0] & 0xFFFFFFFFL;
            byte[] encoded = rest.getBytes(StandardCharsets.UTF_8);
            ex.memory.write((int) (ptr + asciiLength), encoded);
            asciiLength += encoded.length;
            ptr = ex.realloc.apply(ptr, grown, asciiLength, 1)[0] & 0xFFFFFFFFL;
        }

        return new long[] {ptr, asciiLength};
    }

    /** Экспорты единственного wasm-инстанса, общего для всех вызовов. */
    private static final class Exports {
        final Memory memory;
        final ExportFunction wasmSolve;
        final ExportFunction addToStackPointer;
        final ExportFunction malloc;
        final ExportFunction realloc;

        Exports(Instance instance) {
            this.memory = instance.memory();
            this.wasmSolve = instance.export("wasm_solve");
            this.addToStackPointer = instance.export("__wbindgen_add_to_stack_pointer");
            this.malloc = instance.export("__wbindgen_export_0");
            this.realloc = instance.export("__wbindgen_export_1");
        }
    }
}
