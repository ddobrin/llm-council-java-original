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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Configuration properties bound from council.yaml.
 * Maps the council members and chairman configuration to Java objects.
 */
@ConfigurationProperties(prefix = "council")
public class CouncilProperties {

    private Map<String, ModelProperties> models;
    private AvailableProperties available;
    private ChairmanProperties chairman;
    private TitleProperties title;

    public Map<String, ModelProperties> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelProperties> models) {
        this.models = models;
    }

    public ChairmanProperties getChairman() {
        return chairman;
    }

    public void setChairman(ChairmanProperties chairman) {
        this.chairman = chairman;
    }

    public TitleProperties getTitle() {
        return title;
    }

    public void setTitle(TitleProperties title) {
        this.title = title;
    }

    public static class ModelProperties {
        private String id;
        private String name;
        private String provider;
        private String modelId;
        private String avatarColor;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModelId() {
            return modelId;
        }

        public void setModelId(String modelId) {
            this.modelId = modelId;
        }

        public String getAvatarColor() {
            return avatarColor;
        }

        public void setAvatarColor(String avatarColor) {
            this.avatarColor = avatarColor;
        }
    }

    public AvailableProperties getAvailable() {
        return available;
    }

    public void setAvailable(AvailableProperties available) {
        this.available = available;
    }

    public static class AvailableProperties {
        private Map<String, ModelProperties> models;

        public Map<String, ModelProperties> getModels() {
            return models;
        }

        public void setModels(Map<String, ModelProperties> models) {
            this.models = models;
        }
    }

    public static class ChairmanProperties {
        private String model;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class TitleProperties {
        private String model;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}
