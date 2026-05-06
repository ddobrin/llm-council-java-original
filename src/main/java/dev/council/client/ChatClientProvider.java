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

import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Common interface for all chat client implementations.
 * Each provider (Anthropic, Google) implements this interface
 * to provide a unified streaming API.
 */
public interface ChatClientProvider {

    /**
     * Returns the model ID this client handles (e.g., "claude-opus-4-6").
     */
    String getModelId();

    /**
     * Generates a streaming response from the model.
     *
     * @param systemMessage the system prompt providing context
     * @param userMessage   the user's query
     * @param chatId        conversation ID used for log correlation across council stages
     * @return a Flux of ChatResponse chunks as they stream in
     */
    Flux<ChatResponse> generateResponse(String systemMessage, String userMessage, String chatId);

    /**
     * Returns whether this client supports structured (JSON schema) output.
     * Clients that support structured output should override this to return true.
     *
     * @return true if generateStructuredResponse() is implemented
     */
    default boolean supportsStructuredOutput() {
        return false;
    }

    /**
     * Generates a structured response from the model using JSON schema.
     * Uses Spring AI's BeanOutputConverter to append schema instructions
     * to the system prompt and parse the JSON response.
     *
     * @param systemMessage the system prompt providing context
     * @param userMessage   the user's query
     * @param chatId        conversation ID used for log correlation across council stages
     * @param outputType    the class to deserialize the JSON response into
     * @param <T>           the response type
     * @return a Mono containing the parsed response object
     * @throws UnsupportedOperationException if structured output is not supported
     */
    default <T> Mono<T> generateStructuredResponse(
            String systemMessage, String userMessage, String chatId, Class<T> outputType) {
        throw new UnsupportedOperationException(
                "Structured output not supported by " + getModelId());
    }
}
