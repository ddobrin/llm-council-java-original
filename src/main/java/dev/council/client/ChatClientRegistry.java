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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry that collects all ChatClientProvider beans and provides lookup by modelId.
 * This enables the CouncilService to dynamically route requests to the appropriate provider.
 */
@Component
public class ChatClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatClientRegistry.class);

    private final Map<String, ChatClientProvider> clientsByModelId;

    public ChatClientRegistry(List<ChatClientProvider> providers) {
        this.clientsByModelId = providers.stream()
                .collect(Collectors.toMap(ChatClientProvider::getModelId, Function.identity()));

        log.info("ChatClientRegistry initialized with {} providers: {}",
                clientsByModelId.size(), clientsByModelId.keySet());
    }

    /**
     * Returns the ChatClientProvider for the given modelId.
     *
     * @param modelId the model identifier (e.g., "claude-opus-4-6")
     * @return the corresponding ChatClientProvider
     * @throws IllegalArgumentException if no provider exists for the modelId
     */
    public ChatClientProvider getClientOrThrow(String modelId) {
        ChatClientProvider client = clientsByModelId.get(modelId);
        if (client == null) {
            throw new IllegalArgumentException("No ChatClientProvider found for modelId: " + modelId
                    + ". Available: " + clientsByModelId.keySet());
        }
        return client;
    }

    /**
     * Checks if a provider exists for the given modelId.
     */
    public boolean hasClient(String modelId) {
        return clientsByModelId.containsKey(modelId);
    }
}
