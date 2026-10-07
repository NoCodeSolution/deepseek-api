"""PoW (proof-of-work) solver для DeepSeek web API.

Порт ``src/pow.mjs``: исполняет ``sha3_wasm_bg.*.wasm`` через wasmtime
и вызывает ``wasm_solve(challenge, prefix="{salt}_{expire_at}_", difficulty)``.
Glue повторяет wbindgen-конвенции (alloc/realloc/stack_pointer) 1:1.
"""
from __future__ import annotations

import struct
import urllib.request
from functools import lru_cache
from pathlib import Path

import wasmtime

from .config import DEEPSEEK_SHA3_WASM, WASM_CACHE_FILE

SUPPORTED_ALGORITHM = "DeepSeekHashV1"


class PowError(Exception):
    """Ошибка получения или решения PoW-челленджа."""


def _load_wasm_bytes() -> bytes:
    cache = Path(WASM_CACHE_FILE)
    if cache.exists():
        return cache.read_bytes()
    request = urllib.request.Request(
        DEEPSEEK_SHA3_WASM, headers={"User-Agent": "deepseek-free-api/2.0"}
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read()
    try:
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(data)
    except OSError:
        pass
    return data


class _DeepSeekHash:
    """Glue поверх sha3_wasm_bg.wasm."""

    def __init__(self) -> None:
        engine = wasmtime.Engine()
        store = wasmtime.Store(engine)
        module = wasmtime.Module(engine, _load_wasm_bytes())
        instance = wasmtime.Instance(store, module, [])
        exports = instance.exports(store)
        self._store = store
        self._memory: wasmtime.Memory = exports["memory"]
        self._wasm_solve = exports["wasm_solve"]
        self._add_to_stack_pointer = exports["__wbindgen_add_to_stack_pointer"]
        self._malloc = exports["__wbindgen_export_0"]
        self._realloc = exports["__wbindgen_export_1"]

    def calculate_hash(
        self, challenge: str, salt: str, difficulty: float, expire_at: int
    ) -> float | None:
        prefix = f"{salt}_{expire_at}_"
        retptr = self._add_to_stack_pointer(self._store, -16)
        try:
            ptr0, len0 = self._encode_string(challenge)
            ptr1, len1 = self._encode_string(prefix)
            self._wasm_solve(
                self._store, retptr, ptr0, len0, ptr1, len1, float(difficulty)
            )
            raw = self._memory.read(self._store, retptr, retptr + 16)
            status = int.from_bytes(raw[0:4], "little", signed=True)
            value = struct.unpack("<d", raw[8:16])[0]
            return None if status == 0 else value
        finally:
            self._add_to_stack_pointer(self._store, 16)

    def _encode_string(self, text: str) -> tuple[int, int]:
        str_length = len(text)
        ptr = self._malloc(self._store, str_length, 1) & 0xFFFFFFFF

        ascii_length = 0
        for char in text:
            if ord(char) > 127:
                break
            ascii_length += 1
        if ascii_length:
            self._memory.write(
                self._store, text[:ascii_length].encode("ascii"), ptr
            )

        if ascii_length != str_length:
            rest = text[ascii_length:]
            grown = ascii_length + len(rest) * 3
            ptr = self._realloc(self._store, ptr, str_length, grown, 1) & 0xFFFFFFFF
            encoded = rest.encode("utf-8")
            self._memory.write(self._store, encoded, ptr + ascii_length)
            ascii_length += len(encoded)
            ptr = self._realloc(self._store, ptr, grown, ascii_length, 1) & 0xFFFFFFFF

        return ptr, ascii_length


@lru_cache(maxsize=1)
def _solver() -> _DeepSeekHash:
    return _DeepSeekHash()


def solve_pow(challenge: dict) -> int:
    """Решает PoW-челлендж, возвращает целочисленный ответ."""
    algorithm = challenge.get("algorithm")
    if algorithm != SUPPORTED_ALGORITHM:
        raise PowError(f"Unsupported PoW algorithm: {algorithm}")

    expire_at = challenge.get("expire_at", challenge.get("expireAt"))
    if not isinstance(expire_at, (int, float)):
        raise PowError("PoW challenge is missing expire_at.")

    answer = _solver().calculate_hash(
        str(challenge["challenge"]),
        str(challenge["salt"]),
        float(challenge["difficulty"]),
        int(expire_at),
    )
    if answer is None or not float(answer).is_integer():
        raise PowError("PoW solver did not return a valid integer answer.")
    return int(answer)
