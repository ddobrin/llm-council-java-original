/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.council.client;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractChatClientTest {

    @Test
    void canBeInstantiatedDirectly_withoutAnonymousSubclass() {
        Supplier<ChatClient> supplier = () -> mock(ChatClient.class);

        AbstractChatClient client = new AbstractChatClient("test-model-id", supplier);

        assertEquals("test-model-id", client.getModelId());
        assertTrue(client.supportsStructuredOutput());
    }

    @Test
    void supplierIsInvokedExactlyOnce_whenCalledSequentially() {
        @SuppressWarnings("unchecked")
        Supplier<ChatClient> supplier = mock(Supplier.class);
        when(supplier.get()).thenReturn(mock(ChatClient.class));

        AbstractChatClient client = new AbstractChatClient("test-model", supplier);
        client.warmup();
        client.warmup();

        verify(supplier, times(1)).get();
    }

    @Test
    void supplierIsInvokedExactlyOnce_under32ThreadContention() throws InterruptedException {
        @SuppressWarnings("unchecked")
        Supplier<ChatClient> supplier = mock(Supplier.class);
        when(supplier.get()).thenAnswer(invocation -> {
            Thread.sleep(5);  // widen the race window
            return mock(ChatClient.class);
        });

        AbstractChatClient client = new AbstractChatClient("test-model", supplier);

        int threadCount = 32;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    client.warmup();
                } catch (Throwable t) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(10, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();

        org.assertj.core.api.Assertions.assertThat(finished).isTrue();
        org.assertj.core.api.Assertions.assertThat(errors.get())
                .as("unexpected throwables under contention").isZero();
        verify(supplier, times(1)).get();
    }

    @Test
    void supplierThrowsOnFirstCall_secondCallReinvokesSupplier() {
        @SuppressWarnings("unchecked")
        Supplier<ChatClient> supplier = mock(Supplier.class);
        when(supplier.get())
                .thenThrow(new RuntimeException("transient init failure"))
                .thenReturn(mock(ChatClient.class));

        AbstractChatClient client = new AbstractChatClient("test-model", supplier);

        org.assertj.core.api.Assertions.assertThatThrownBy(client::warmup)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("transient init failure");

        client.warmup();  // second call should re-invoke supplier (no cached failure)

        verify(supplier, times(2)).get();
    }
}
