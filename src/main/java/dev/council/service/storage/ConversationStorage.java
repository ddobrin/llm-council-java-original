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
package dev.council.service.storage;

import dev.council.model.CouncilSession;
import dev.council.model.SavedSession;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class ConversationStorage {

    private static final Logger log = LoggerFactory.getLogger(ConversationStorage.class);

    /** Pattern for valid session filenames: lowercase alphanumeric/hyphens + 8-char UUID prefix + .json */
    private static final Pattern FILENAME_PATTERN = Pattern.compile("^[a-z0-9-]+-[a-f0-9]{8}\\.json$");

    /** Pattern for valid GCS filenames: title/timestamp.json (e.g., "my-topic/2024-01-01T10-00-00-000Z.json") */
    private static final Pattern GCS_FILENAME_PATTERN = Pattern.compile("^[a-zA-Z0-9-_ ]+/[0-9T\\-Z]+\\.json$");

    /** Pattern for valid UUID format */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final ObservationRegistry observationRegistry;
    private final Path storageDir;
    private final String bucketName;
    private volatile boolean directoryInitialized;

    public ConversationStorage(
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry,
            @Value("${conversation.dir}") String conversationDir,
            @Value("${bucket.name}") String bucketName) {
        this.objectMapper = objectMapper;
        this.observationRegistry = observationRegistry;
        this.storageDir = Path.of(conversationDir);
        this.bucketName = bucketName;
    }

    private void ensureDirectoryExists() throws IOException {
        if (!directoryInitialized) {
            synchronized (this) {
                if (!directoryInitialized) {
                    Files.createDirectories(storageDir);
                    log.info("Conversation storage directory: {}", storageDir.toAbsolutePath());
                    directoryInitialized = true;
                }
            }
        }
    }

    public SavedSession saveSession(String title, CouncilSession session) throws IOException {
        ensureDirectoryExists();
        return Observation.createNotStarted("council.session.save", observationRegistry)
                .highCardinalityKeyValue("council.session.id", session.id())
                .observe(() -> {
                    validateSessionId(session.id());
                    String filename = sanitizeFilename(title) + "-" + session.id().substring(0, 8) + ".json";
                    SavedSession saved = new SavedSession(title, filename, session);

                    Path file = storageDir.resolve(filename);
                    objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), saved);
                    log.info("Saved session '{}' to {}", title, file);
                    return saved;
                });
    }

    public List<SavedSession> listSessions() throws IOException {
        ensureDirectoryExists();
        if (!Files.exists(storageDir)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(storageDir)) {
            return paths
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(p -> {
                        try {
                            return -Files.getLastModifiedTime((Path) p).toMillis();
                        } catch (Exception e) {
                            return 0L;
                        }
                    }))
                    .map(p -> {
                        try {
                            return objectMapper.readValue(p.toFile(), SavedSession.class);
                        } catch (Exception e) {
                            log.warn("Failed to read session file {}: {}", p, e.getMessage());
                            return null;
                        }
                    })
                    .filter(s -> s != null)
                    .toList();
        }
    }

    public SavedSession loadSession(String filename) throws IOException {
        ensureDirectoryExists();
        Path file = validateAndResolvePath(filename);
        return objectMapper.readValue(file.toFile(), SavedSession.class);
    }


    private volatile Storage storage;

    protected Storage getStorage() {
        if (this.storage == null) {
            synchronized (this) {
                if (this.storage == null) {
                    this.storage = StorageOptions.getDefaultInstance().getService();
                }
            }
        }
        return this.storage;
    }

    // For testing
    void setStorage(Storage storage) {
        this.storage = storage;
    }

    public SavedSession saveSessionGcs(String title, CouncilSession session) {
        validateSessionId(session.id());

        String safeTitle = sanitizeFilename(title);
        String timestamp = Instant.now().toString().replace(":", "-").replace(".", "-");
        String objectName = safeTitle + "/" + timestamp + ".json";

        try {
            Storage gcs = getStorage();
            BlobId blobId = BlobId.of(bucketName, objectName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).setContentType("application/json").build();

            String jsonContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(session);
            gcs.create(blobInfo, jsonContent.getBytes(StandardCharsets.UTF_8));

            log.info("Saved session {} to GCS: gs://{}/{}", session.id(), bucketName, objectName);
            return new SavedSession(title, objectName, session);
        } catch (StorageException e) {
            log.error("Failed to save session {} to GCS: {}", session.id(), e.getMessage(), e);
            throw new RuntimeException("Failed to save session to GCS", e);
        } catch (Exception e) {
            log.error("Failed to serialize session {} for GCS: {}", session.id(), e.getMessage(), e);
            throw new RuntimeException("Failed to serialize session for GCS", e);
        }
    }

    public List<SavedSession> listGcsSessions() {
        List<SavedSession> sessions = new ArrayList<>();

        try {
            Storage gcs = getStorage();
            Page<Blob> blobs = gcs.list(bucketName);

            for (Blob blob : blobs.iterateAll()) {
                String blobName = blob.getName();

                // Skip directory markers and non-JSON files
                if (blobName.endsWith("/") || !blobName.endsWith(".json")) {
                    continue;
                }

                try {
                    // Extract title from path: "title/timestamp.json" -> "title"
                    String title = extractTitleFromBlobName(blobName);

                    // Download and deserialize CouncilSession
                    byte[] content = blob.getContent();
                    CouncilSession councilSession = objectMapper.readValue(content, CouncilSession.class);

                    // Wrap in SavedSession
                    sessions.add(new SavedSession(title, blobName, councilSession));
                } catch (Exception e) {
                    log.warn("Failed to read GCS session {}: {}", blobName, e.getMessage());
                }
            }

            // Sort by timestamp in filename (newest first)
            sessions.sort((a, b) -> extractTimestamp(b.filename()).compareTo(extractTimestamp(a.filename())));

            return List.copyOf(sessions);
        } catch (StorageException e) {
            log.error("Failed to list GCS sessions: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Loads a single session from GCS by filename.
     *
     * @param fileName the GCS object name (e.g., "my-topic/2024-01-01T10-00-00Z.json")
     * @return the SavedSession, or null if the file doesn't exist
     * @throws IllegalArgumentException if the filename format is invalid
     * @throws RuntimeException if GCS access or deserialization fails
     */
    public SavedSession loadSessionGcs(String fileName) {
        validateGcsFileName(fileName);

        try {
            Storage gcs = getStorage();
            Blob blob = gcs.get(BlobId.of(bucketName, fileName));

            if (blob == null || !blob.exists()) {
                log.debug("GCS session not found: {}", fileName);
                return null;
            }

            byte[] content = blob.getContent();
            CouncilSession session = objectMapper.readValue(content, CouncilSession.class);
            String title = extractTitleFromBlobName(fileName);

            log.info("Loaded session from GCS: gs://{}/{}", bucketName, fileName);
            return new SavedSession(title, fileName, session);
        } catch (StorageException e) {
            log.error("Failed to load session from GCS {}: {}", fileName, e.getMessage(), e);
            throw new RuntimeException("Failed to load session from GCS", e);
        } catch (Exception e) {
            log.error("Failed to deserialize session from GCS {}: {}", fileName, e.getMessage(), e);
            throw new RuntimeException("Failed to deserialize session from GCS", e);
        }
    }

    /**
     * Validates GCS filename format to prevent path injection attacks.
     */
    private void validateGcsFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }

        // Block path traversal attempts
        if (fileName.contains("..") || fileName.startsWith("/")) {
            log.warn("GCS path traversal attempt rejected: {}", fileName);
            throw new IllegalArgumentException("Invalid GCS filename: path traversal not allowed");
        }

        // Validate format matches expected pattern
        if (!GCS_FILENAME_PATTERN.matcher(fileName).matches()) {
            log.warn("Invalid GCS filename format rejected: {}", fileName);
            throw new IllegalArgumentException("Invalid GCS filename format");
        }
    }

    private String extractTitleFromBlobName(String blobName) {
        int slashIndex = blobName.lastIndexOf('/');
        return (slashIndex > 0) ? blobName.substring(0, slashIndex) : blobName.replace(".json", "");
    }

    private String extractTimestamp(String filename) {
        int slashIndex = filename.lastIndexOf('/');
        String timestampPart = (slashIndex >= 0) ? filename.substring(slashIndex + 1) : filename;
        return timestampPart.replace(".json", "");
    }

    static String sanitizeFilename(String title) {
        String sanitized = title.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (sanitized.length() > 50) {
            sanitized = sanitized.substring(0, 50);
        }
        if (sanitized.isEmpty()) {
            sanitized = "untitled";
        }
        return sanitized;
    }

    /**
     * Validates that a session ID is a properly formatted UUID.
     * Defense-in-depth: sessionIds are generated server-side, but validation prevents
     * any potential injection if client data ever reaches this method.
     */
    private void validateSessionId(String sessionId) {
        if (sessionId == null || !UUID_PATTERN.matcher(sessionId).matches()) {
            log.error("Invalid session ID format: {}", sessionId);
            throw new IllegalArgumentException("Session ID must be a valid UUID");
        }
    }

    /**
     * Validates and resolves a filename to prevent path traversal attacks.
     * Ensures the resolved path stays within the storage directory.
     */
    private Path validateAndResolvePath(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename cannot be null or empty");
        }

        // Only allow: lowercase alphanumeric, hyphens, 8-char uuid prefix, .json
        if (!FILENAME_PATTERN.matcher(filename).matches()) {
            log.warn("Invalid filename format rejected: {}", filename);
            throw new IllegalArgumentException("Invalid filename format");
        }

        Path resolved = storageDir.toAbsolutePath().resolve(filename).normalize();
        Path normalizedBase = storageDir.toAbsolutePath().normalize();

        if (!resolved.startsWith(normalizedBase)) {
            log.error("Path traversal attempt detected: {}", filename);
            throw new SecurityException("Invalid file path: directory traversal not allowed");
        }

        return resolved;
    }
}
