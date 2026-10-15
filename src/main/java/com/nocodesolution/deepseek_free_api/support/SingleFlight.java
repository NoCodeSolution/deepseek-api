package com.nocodesolution.deepseek_free_api.support;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Single-flight: параллельные вызовы дожидаются одного выполнения работы.
 * Результат не кэшируется между вызовами — каждый следующий вызов запускает
 * work заново. Это нужно для refresh-операций, которые должны всегда пытаться
 * обновить состояние, но не должны гоняться между собой.
 */
public final class SingleFlight<T> { // TODO посмотреть альтернативы

    private final Lock lock = new ReentrantLock();
    private Call<T> current;

    /**
     * Выполняет work один раз для всех одновременных вызовов.
     */
    public T execute(Supplier<T> work) {
        Call<T> call = null;
        boolean owner;
        lock.lock();
        try {
            if (current == null) {
                current = new Call<>();
                owner = true;
            } else {
                call = current;
                owner = false;
            }
        } finally {
            lock.unlock();
        }

        if (!owner) {
            return call.await();
        }

        try {
            T result = work.get();
            current.done(result, null);
            return result;
        } catch (RuntimeException e) {
            current.done(null, e);
            throw e;
        } finally {
            lock.lock();
            try {
                current = null;
            } finally {
                lock.unlock();
            }
        }
    }

    private static final class Call<R> {
        private final CountDownLatch latch = new CountDownLatch(1);
        private R result;
        private RuntimeException exception;

        R await() {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            if (exception != null) {
                throw exception;
            }
            return result;
        }

        void done(R result, RuntimeException exception) {
            this.result = result;
            this.exception = exception;
            latch.countDown();
        }
    }
}
