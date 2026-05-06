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
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized factory for all ChatClientProvider beans.
 * Replaces the per-model @Component classes with a single @Configuration
 * whose @Bean methods construct AbstractChatClient instances directly via
 * anonymous subclassing.
 *
 * <p>To add a new Anthropic or Gemini model: add one @Value field block
 * + one @Bean method that calls the matching helper. To add a new provider
 * (e.g. OpenAI, Ollama): add a buildXxxChatClient static factory in
 * AbstractChatClient + a new helper here.</p>
 */
@Configuration
public class ChatClientConfig {

    // ── Anthropic (shared across Opus/Sonnet/Haiku) ──────────────────
    @Value("${vertex.ai.anthropic.enabled:false}")   private boolean vertexEnabled;
    @Value("${vertex.ai.anthropic.project-id:}")     private String  vertexProjectId;
    @Value("${vertex.ai.anthropic.location:global}") private String  vertexLocation;

    // ── Anthropic — Opus 4.6 ─────────────────────────────────────────
    @Value("${spring.ai.anthropic.opus46.api-key:}")                       private String opusApiKey;
    @Value("${spring.ai.anthropic.opus46.chat.options.model}")             private String opusModel;
    @Value("${spring.ai.anthropic.opus46.chat.options.max-tokens:8192}")   private int    opusMaxTokens;

    // ── Anthropic — Sonnet 4.6 ───────────────────────────────────────
    @Value("${spring.ai.anthropic.sonnet46.api-key:}")                     private String sonnetApiKey;
    @Value("${spring.ai.anthropic.sonnet46.chat.options.model}")           private String sonnetModel;
    @Value("${spring.ai.anthropic.sonnet46.chat.options.max-tokens:8192}") private int    sonnetMaxTokens;

    // ── Anthropic — Haiku 4.5 ────────────────────────────────────────
    @Value("${spring.ai.anthropic.haiku45.api-key:}")                      private String haikuApiKey;
    @Value("${spring.ai.anthropic.haiku45.chat.options.model}")            private String haikuModel;
    // Reads haiku45.max-tokens (was sonnet46.max-tokens in the old per-model class — silent inheritance bug).
    @Value("${spring.ai.anthropic.haiku45.chat.options.max-tokens:8192}")  private int    haikuMaxTokens;

    // ── Gemini — Flash 3 ─────────────────────────────────────────────
    @Value("${spring.ai.google.genai.flash3.project-id}")                   private String flash3ProjectId;
    @Value("${spring.ai.google.genai.flash3.location}")                     private String flash3Location;
    @Value("${spring.ai.google.genai.flash3.chat.options.model}")           private String flash3Model;
    @Value("${spring.ai.google.genai.flash3.chat.options.temperature:1.0}") private double flash3Temperature;

    // ── Gemini — Flash 3.1 Lite ──────────────────────────────────────
    @Value("${spring.ai.google.genai.flash31lite.project-id}")                   private String flash31LiteProjectId;
    @Value("${spring.ai.google.genai.flash31lite.location}")                     private String flash31LiteLocation;
    @Value("${spring.ai.google.genai.flash31lite.chat.options.model}")           private String flash31LiteModel;
    @Value("${spring.ai.google.genai.flash31lite.chat.options.temperature:1.0}") private double flash31LiteTemperature;

    // ── Gemini — Pro 3.1 ─────────────────────────────────────────────
    @Value("${spring.ai.google.genai.pro31.project-id}")                   private String pro31ProjectId;
    @Value("${spring.ai.google.genai.pro31.location}")                     private String pro31Location;
    @Value("${spring.ai.google.genai.pro31.chat.options.model}")           private String pro31Model;
    @Value("${spring.ai.google.genai.pro31.chat.options.temperature:1.0}") private double pro31Temperature;

    private final ObservationRegistry observationRegistry;

    public ChatClientConfig(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    // ── Anthropic Beans ──────────────────────────────────────────────

    @Bean
    public ChatClientProvider claudeOpus46() {
        return anthropicProvider("claude-opus-4-6", opusApiKey, opusModel, opusMaxTokens);
    }

    @Bean
    public ChatClientProvider claudeSonnet46() {
        return anthropicProvider("claude-sonnet-4-6", sonnetApiKey, sonnetModel, sonnetMaxTokens);
    }

    @Bean
    public ChatClientProvider claudeHaiku45() {
        return anthropicProvider("claude-haiku-4-5", haikuApiKey, haikuModel, haikuMaxTokens);
    }

    // ── Gemini Beans ─────────────────────────────────────────────────

    @Bean
    public ChatClientProvider gemini3Flash() {
        return geminiProvider("gemini-3-flash-preview",
                flash3ProjectId, flash3Location, flash3Model, flash3Temperature);
    }

    @Bean
    public ChatClientProvider gemini31FlashLite() {
        return geminiProvider("gemini-3.1-flash-lite-preview",
                flash31LiteProjectId, flash31LiteLocation, flash31LiteModel, flash31LiteTemperature);
    }

    @Bean
    public ChatClientProvider gemini31Pro() {
        return geminiProvider("gemini-3.1-pro-preview",
                pro31ProjectId, pro31Location, pro31Model, pro31Temperature);
    }

    // ── Private factory helpers (Style B deduplication) ──────────────

    private ChatClientProvider anthropicProvider(
            String beanModelId, String apiKey, String model, int maxTokens) {
        return new AbstractChatClient(beanModelId,
                () -> vertexEnabled
                        ? AbstractChatClient.buildVertexAiAnthropicChatClient(
                                vertexProjectId, vertexLocation, model, maxTokens, observationRegistry)
                        : AbstractChatClient.buildAnthropicChatClient(
                                apiKey, model, maxTokens, observationRegistry));
    }

    private ChatClientProvider geminiProvider(
            String beanModelId, String projectId, String location,
            String model, double temperature) {
        return new AbstractChatClient(beanModelId,
                () -> AbstractChatClient.buildGeminiChatClient(
                        projectId, location, model, temperature, observationRegistry));
    }
}
