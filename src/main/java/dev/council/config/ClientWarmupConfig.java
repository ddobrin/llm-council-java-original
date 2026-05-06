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
package dev.council.config;

import dev.council.client.AbstractChatClient;
import dev.council.client.ChatClientProvider;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Configuration
@ConditionalOnProperty(name = "council.warmup.enabled", havingValue = "true", matchIfMissing = true)
public class ClientWarmupConfig {

    private static final Logger log = LoggerFactory.getLogger(ClientWarmupConfig.class);

    private final List<ChatClientProvider> providers;

    public ClientWarmupConfig(List<ChatClientProvider> providers) {
        this.providers = providers;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupClients() {
        log.info("Starting ChatClient warm-up for {} providers", providers.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ChatClientProvider provider : providers) {
                if (provider instanceof AbstractChatClient client) {
                    executor.submit(() -> {
                        try {
                            client.warmup();
                        } catch (Exception e) {
                            log.warn("Warm-up failed for {}: {}", provider.getModelId(), e.getMessage());
                        }
                    });
                }
            }
        }
        log.info("ChatClient warm-up complete");
    }
}
