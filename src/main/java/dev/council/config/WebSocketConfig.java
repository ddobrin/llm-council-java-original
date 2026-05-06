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

import org.apache.catalina.Context;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * WebSocket configuration to increase buffer sizes for large LLM responses.
 *
 * The default Tomcat WebSocket text buffer is 8KB, which is insufficient for
 * Stage 2 peer ranking responses where each model sends a full evaluation
 * of all other responses.
 */
@Configuration
public class WebSocketConfig {

    // 1MB buffer to accommodate large LLM responses
    private static final String MAX_TEXT_MESSAGE_BUFFER_SIZE = "1048576";  // 1MB

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatWebSocketCustomizer() {
        return factory -> factory.addContextCustomizers(context -> {
            // Set WebSocket buffer sizes via servlet context init parameters
            // These are read by Tomcat's WebSocket implementation
            context.addParameter("org.apache.tomcat.websocket.textBufferSize", MAX_TEXT_MESSAGE_BUFFER_SIZE);
            context.addParameter("org.apache.tomcat.websocket.binaryBufferSize", MAX_TEXT_MESSAGE_BUFFER_SIZE);

            // Also add as servlet context listener for when parameters are read later
            context.addApplicationListener(WebSocketBufferConfigListener.class.getName());
        });
    }

    /**
     * Listener that sets WebSocket buffer sizes at context initialization.
     * This is a fallback in case the context parameters are read after initialization.
     */
    public static class WebSocketBufferConfigListener implements ServletContextListener {
        @Override
        public void contextInitialized(ServletContextEvent sce) {
            // Set the default text buffer size for all WebSocket connections
            sce.getServletContext().setInitParameter(
                "org.apache.tomcat.websocket.textBufferSize",
                MAX_TEXT_MESSAGE_BUFFER_SIZE
            );
            sce.getServletContext().setInitParameter(
                "org.apache.tomcat.websocket.binaryBufferSize",
                MAX_TEXT_MESSAGE_BUFFER_SIZE
            );
        }
    }
}
