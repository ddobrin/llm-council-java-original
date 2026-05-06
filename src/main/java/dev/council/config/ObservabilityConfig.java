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

import com.google.cloud.opentelemetry.trace.TraceExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;

import java.util.Collection;

@Configuration
public class ObservabilityConfig {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityConfig.class);

    @Bean
    @Lazy
    @Profile("!local")
    public SpanExporter googleCloudTraceExporter() {
        log.info("Configuring Google Cloud Trace exporter (project from GOOGLE_CLOUD_PROJECT env var)");
        return TraceExporter.createWithDefaultConfiguration();
    }

    @Bean
    @Profile("local")
    public SpanExporter loggingSpanExporter() {
        log.info("Configuring logging span exporter for local development");
        return new Slf4jSpanExporter();
    }

    private static class Slf4jSpanExporter implements SpanExporter {
        private static final Logger spanLog = LoggerFactory.getLogger("otel.spans");

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            for (SpanData span : spans) {
                spanLog.info("[{}] {} ({}ms) attributes={}",
                        span.getTraceId(),
                        span.getName(),
                        (span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000_000,
                        span.getAttributes());
            }
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
