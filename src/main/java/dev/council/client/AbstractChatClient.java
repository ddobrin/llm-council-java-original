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

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync;
import com.anthropic.vertex.backends.VertexBackend;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/**
 * Abstract base class for all ChatClientProvider implementations.
 * Encapsulates the shared ChatClient construction and the four
 * ChatClientProvider method implementations.
 *
 * <p>Subclasses only need to pass a model ID and a pre-built ChatClient
 * (via the static factory methods) to the constructor.</p>
 */
public class AbstractChatClient implements ChatClientProvider {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final String modelId;
    private final Supplier<ChatClient> chatClientSupplier;
    private volatile ChatClient chatClient;

    public AbstractChatClient(String modelId, Supplier<ChatClient> chatClientSupplier) {
        this.modelId = modelId;
        this.chatClientSupplier = chatClientSupplier;
        log.info("ChatClientProvider registered for model: {}", modelId);
    }

    private ChatClient getChatClient() {
        if (chatClient == null) {
            synchronized (this) {
                if (chatClient == null) {
                    log.info("Initializing ChatClient for model: {}", modelId);
                    chatClient = chatClientSupplier.get();
                }
            }
        }
        return chatClient;
    }

    // ── Static factory methods ──────────────────────────────────────────

    public static ChatClient buildAnthropicChatClient(
            String apiKey, String model, int maxTokens,
            ObservationRegistry observationRegistry) {

        var chatOptions = AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(maxTokens)
                .apiKey(apiKey)
                .build();

        var chatModel = AnthropicChatModel.builder()
                .options(chatOptions)
                .observationRegistry(observationRegistry)
                .build();

        return buildChatClient(chatModel);
    }

    public static ChatClient buildVertexAiAnthropicChatClient(
            String projectId, String location, String model, int maxTokens,
            ObservationRegistry observationRegistry) {

        GoogleCredentials credentials;
        try {
            credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to obtain Google Application Default Credentials", e);
        }

        var vertexBackend = VertexBackend.builder()
                .googleCredentials(credentials)
                .region(location)
                .project(projectId)
                .build();

        var anthropicClient = AnthropicOkHttpClient.builder()
                .backend(vertexBackend)
                .build();

        var anthropicClientAsync = AnthropicOkHttpClientAsync.builder()
                .backend(vertexBackend)
                .build();

        var chatOptions = AnthropicChatOptions.builder()
                .model(model)
                .maxTokens(maxTokens)
                .build();

        var chatModel = AnthropicChatModel.builder()
                .anthropicClient(anthropicClient)
                .anthropicClientAsync(anthropicClientAsync)
                .options(chatOptions)
                .observationRegistry(observationRegistry)
                .build();

        return buildChatClient(chatModel);
    }

    public static ChatClient buildGeminiChatClient(
            String projectId, String location, String model,
            double temperature, ObservationRegistry observationRegistry) {

        Client genAiClient = Client.builder()
                .project(projectId)
                .location(location)
                .vertexAI(true)
                .build();

        var chatOptions = GoogleGenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        var chatModel = GoogleGenAiChatModel.builder()
                .genAiClient(genAiClient)
                .defaultOptions(chatOptions)
                .observationRegistry(observationRegistry)
                .build();

        return buildChatClient(chatModel);
    }

    private static ChatClient buildChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /**
     * Eagerly initializes the underlying ChatClient (triggers the deferred Supplier).
     * Called by background warm-up after startup.
     */
    public void warmup() {
        getChatClient();
    }

    // ── ChatClientProvider implementation ───────────────────────────────

    @Override
    public String getModelId() {
        return modelId;
    }

    @Override
    public Flux<ChatResponse> generateResponse(
            String systemMessage, String userMessage, String chatId) {
        log.debug("Generating response from {} for chatId: {}", modelId, chatId);

        return getChatClient().prompt()
                .system(systemMessage)
                .user(userMessage)
                .stream()
                .chatResponse()
                .doOnComplete(() -> log.debug(">>> STREAM COMPLETE for {} chatId: {}", modelId, chatId))
                .doOnError(e -> log.error("Error streaming from {}: {}", modelId, e.getMessage()));
    }

    @Override
    public boolean supportsStructuredOutput() {
        return true;
    }

    @Override
    public <T> Mono<T> generateStructuredResponse(
            String systemMessage, String userMessage, String chatId, Class<T> outputType) {
        log.debug("Generating structured response from {} for chatId: {}", modelId, chatId);

        var converter = new BeanOutputConverter<>(outputType);
        String enhancedSystem = systemMessage + "\n\n" + converter.getFormat();

        return Mono.fromCallable(() ->
                getChatClient().prompt()
                        .system(enhancedSystem)
                        .user(userMessage)
                        .call()
                        .content())
                .map(converter::convert)
                .doOnSuccess(result -> log.debug(">>> STRUCTURED RESPONSE COMPLETE for {} chatId: {}", modelId, chatId))
                .doOnError(e -> log.error("Error generating structured response from {}: {}", modelId, e.getMessage()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
