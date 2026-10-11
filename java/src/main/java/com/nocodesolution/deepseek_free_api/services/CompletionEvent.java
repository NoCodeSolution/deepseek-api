package com.nocodesolution.deepseek_free_api.services;

/** Событие оркестрации completion. */
public sealed interface CompletionEvent {

    /** Промежуточный кусок ответа: content и/или reasoning (пустые строки допустимы). */
    record TextDelta(String content, String reasoning) implements CompletionEvent {
    }

    /** Финал: полный текст, рассуждение и отправленный (санитизированный) prompt. */
    record Done(String text, String reasoning, String prompt) implements CompletionEvent {
    }

    /** Ошибка попытки (после исчерпания retry). */
    record ErrorEvent(Throwable cause) implements CompletionEvent {
    }
}
