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

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import dev.council.model.CouncilSession;
import dev.council.model.SavedSession;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ConversationStorageTest {

    private ConversationStorage storageService;
    private ObjectMapper objectMapper;
    private ObservationRegistry observationRegistry;
    private Storage mockStorage;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("council-test");
        objectMapper = new ObjectMapper();
        observationRegistry = ObservationRegistry.create();
        mockStorage = mock(Storage.class);
        
        storageService = new ConversationStorage(objectMapper, observationRegistry, tempDir.toString(), "llm-council-conversations");
        storageService.setStorage(mockStorage);
    }

    @Test
    void saveSessionGcs_success() {
        CouncilSession session = new CouncilSession(
            "550e8400-e29b-41d4-a716-446655440000",
            "Topic",
            java.time.Instant.now(),
            CouncilSession.CouncilStage.PENDING,
            List.of(),
            List.of(),
            java.util.Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            null
        );
        
        SavedSession result = storageService.saveSessionGcs("title", session);

        ArgumentCaptor<BlobInfo> blobInfoCaptor = ArgumentCaptor.forClass(BlobInfo.class);
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);

        verify(mockStorage).create(blobInfoCaptor.capture(), bytesCaptor.capture());

        BlobInfo blobInfo = blobInfoCaptor.getValue();
        assertEquals("llm-council-conversations", blobInfo.getBucket());
        assertTrue(blobInfo.getName().startsWith("title/"));
        assertTrue(blobInfo.getName().endsWith(".json"));
        assertEquals("application/json", blobInfo.getContentType());

        assertTrue(bytesCaptor.getValue().length > 0);

        // Verify returned SavedSession
        assertEquals("title", result.title());
        assertEquals(blobInfo.getName(), result.filename());
        assertEquals(session, result.session());
    }

    @Test
    void saveSessionGcs_invalidUuid_throwsException() {
        CouncilSession session = new CouncilSession(
            "invalid-uuid",
            "Topic",
            java.time.Instant.now(),
            CouncilSession.CouncilStage.PENDING,
            List.of(),
            List.of(),
            java.util.Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            null
        );
        
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            storageService.saveSessionGcs("title", session);
        });
        
        assertEquals("Session ID must be a valid UUID", thrown.getMessage());
        verifyNoInteractions(mockStorage);
    }

    @Test
    void saveSessionGcs_storageException_throwsRuntimeException() {
        CouncilSession session = new CouncilSession(
            "550e8400-e29b-41d4-a716-446655440000",
            "Topic",
            java.time.Instant.now(),
            CouncilSession.CouncilStage.PENDING,
            List.of(),
            List.of(),
            java.util.Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            null
        );

        when(mockStorage.create(any(BlobInfo.class), any(byte[].class)))
            .thenThrow(new StorageException(500, "Internal Server Error"));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            storageService.saveSessionGcs("title", session);
        });

        assertEquals("Failed to save session to GCS", thrown.getMessage());
        assertTrue(thrown.getCause() instanceof StorageException);
    }

    @Test
    void saveSessionGcs_titleWithPathSeparator_isSanitized() {
        CouncilSession session = createTestSession("550e8400-e29b-41d4-a716-446655440010");

        SavedSession result = storageService.saveSessionGcs("evil/../etc/passwd", session);

        ArgumentCaptor<BlobInfo> blobInfoCaptor = ArgumentCaptor.forClass(BlobInfo.class);
        verify(mockStorage).create(blobInfoCaptor.capture(), any(byte[].class));

        String objectName = blobInfoCaptor.getValue().getName();
        assertFalse(objectName.contains(".."), "Sanitized name must not contain '..'");
        assertEquals(1, objectName.chars().filter(c -> c == '/').count(),
                "Sanitized name must contain exactly one '/' (between title and timestamp)");
        assertTrue(objectName.startsWith("evil-etc-passwd/"),
                "Expected sanitized title prefix, got: " + objectName);
    }

    @Test
    void saveSessionGcs_titleWithSpecialChars_isLowercasedAndHyphenated() {
        CouncilSession session = createTestSession("550e8400-e29b-41d4-a716-446655440011");

        SavedSession result = storageService.saveSessionGcs("Hello, World!", session);

        ArgumentCaptor<BlobInfo> blobInfoCaptor = ArgumentCaptor.forClass(BlobInfo.class);
        verify(mockStorage).create(blobInfoCaptor.capture(), any(byte[].class));

        assertTrue(blobInfoCaptor.getValue().getName().startsWith("hello-world/"),
                "Expected lowercased+hyphenated title, got: " + blobInfoCaptor.getValue().getName());
    }

    @Test
    void saveSessionGcs_emptyTitle_fallsBackToUntitled() {
        CouncilSession session = createTestSession("550e8400-e29b-41d4-a716-446655440012");

        SavedSession result = storageService.saveSessionGcs("!!!", session);

        ArgumentCaptor<BlobInfo> blobInfoCaptor = ArgumentCaptor.forClass(BlobInfo.class);
        verify(mockStorage).create(blobInfoCaptor.capture(), any(byte[].class));

        assertTrue(blobInfoCaptor.getValue().getName().startsWith("untitled/"),
                "Expected 'untitled/' fallback for sanitized-to-empty title, got: "
                        + blobInfoCaptor.getValue().getName());
    }

    // Helper method for creating mock blobs
    private Blob createMockBlob(String name, byte[] content) {
        Blob blob = mock(Blob.class);
        when(blob.getName()).thenReturn(name);
        when(blob.getContent()).thenReturn(content);
        return blob;
    }

    private CouncilSession createTestSession(String id) {
        return new CouncilSession(
            id,
            "Topic",
            java.time.Instant.now(),
            CouncilSession.CouncilStage.PENDING,
            List.of(),
            List.of(),
            java.util.Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            null
        );
    }

    @SuppressWarnings("unchecked")
    @Test
    void listGcsSessions_success_returnsSessionsSortedByNewest() throws Exception {
        CouncilSession session1 = createTestSession("550e8400-e29b-41d4-a716-446655440001");
        CouncilSession session2 = createTestSession("550e8400-e29b-41d4-a716-446655440002");

        byte[] content1 = objectMapper.writeValueAsBytes(session1);
        byte[] content2 = objectMapper.writeValueAsBytes(session2);

        Blob blob1 = createMockBlob("my-topic/2024-01-01T10-00-00Z.json", content1);
        Blob blob2 = createMockBlob("my-topic/2024-01-02T10-00-00Z.json", content2);

        Page<Blob> mockPage = mock(Page.class);
        when(mockPage.iterateAll()).thenReturn(Arrays.asList(blob1, blob2));
        when(mockStorage.list("llm-council-conversations")).thenReturn(mockPage);

        List<SavedSession> sessions = storageService.listGcsSessions();

        assertEquals(2, sessions.size());
        // Verify sorted newest first (2024-01-02 before 2024-01-01)
        assertEquals("my-topic/2024-01-02T10-00-00Z.json", sessions.get(0).filename());
        assertEquals("my-topic/2024-01-01T10-00-00Z.json", sessions.get(1).filename());
        assertEquals("my-topic", sessions.get(0).title());
    }

    @SuppressWarnings("unchecked")
    @Test
    void listGcsSessions_emptyBucket_returnsEmptyList() {
        Page<Blob> mockPage = mock(Page.class);
        when(mockPage.iterateAll()).thenReturn(List.of());
        when(mockStorage.list("llm-council-conversations")).thenReturn(mockPage);

        List<SavedSession> sessions = storageService.listGcsSessions();

        assertTrue(sessions.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void listGcsSessions_partialFailure_returnsValidSessions() throws Exception {
        CouncilSession validSession = createTestSession("550e8400-e29b-41d4-a716-446655440003");
        byte[] validContent = objectMapper.writeValueAsBytes(validSession);

        Blob validBlob = createMockBlob("valid-topic/2024-01-01T10-00-00Z.json", validContent);
        Blob invalidBlob = createMockBlob("invalid-topic/2024-01-02T10-00-00Z.json", "not valid json".getBytes());

        Page<Blob> mockPage = mock(Page.class);
        when(mockPage.iterateAll()).thenReturn(Arrays.asList(validBlob, invalidBlob));
        when(mockStorage.list("llm-council-conversations")).thenReturn(mockPage);

        List<SavedSession> sessions = storageService.listGcsSessions();

        assertEquals(1, sessions.size());
        assertEquals("valid-topic", sessions.get(0).title());
    }

    @Test
    void listGcsSessions_storageException_returnsEmptyList() {
        when(mockStorage.list("llm-council-conversations"))
            .thenThrow(new StorageException(500, "Internal Server Error"));

        List<SavedSession> sessions = storageService.listGcsSessions();

        assertTrue(sessions.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void listGcsSessions_skipsDirectoriesAndNonJson() throws Exception {
        CouncilSession session = createTestSession("550e8400-e29b-41d4-a716-446655440004");
        byte[] content = objectMapper.writeValueAsBytes(session);

        Blob validBlob = createMockBlob("topic/2024-01-01T10-00-00Z.json", content);
        Blob directoryBlob = createMockBlob("some-directory/", new byte[0]);
        Blob txtBlob = createMockBlob("topic/file.txt", "text content".getBytes());

        Page<Blob> mockPage = mock(Page.class);
        when(mockPage.iterateAll()).thenReturn(Arrays.asList(validBlob, directoryBlob, txtBlob));
        when(mockStorage.list("llm-council-conversations")).thenReturn(mockPage);

        List<SavedSession> sessions = storageService.listGcsSessions();

        assertEquals(1, sessions.size());
        assertEquals("topic/2024-01-01T10-00-00Z.json", sessions.get(0).filename());
    }

    // ===== loadSessionGcs tests =====

    @Test
    void loadSessionGcs_existingFile_returnsSession() throws Exception {
        String fileName = "my-topic/2024-01-01T10-00-00Z.json";
        CouncilSession session = createTestSession("550e8400-e29b-41d4-a716-446655440005");
        byte[] content = objectMapper.writeValueAsBytes(session);

        Blob mockBlob = mock(Blob.class);
        when(mockBlob.exists()).thenReturn(true);
        when(mockBlob.getContent()).thenReturn(content);
        when(mockStorage.get(BlobId.of("llm-council-conversations", fileName))).thenReturn(mockBlob);

        SavedSession result = storageService.loadSessionGcs(fileName);

        assertNotNull(result);
        assertEquals("my-topic", result.title());
        assertEquals(fileName, result.filename());
        assertEquals(session.id(), result.session().id());
    }

    @Test
    void loadSessionGcs_nonExistentFile_returnsNull() {
        String fileName = "missing-topic/2024-01-01T10-00-00Z.json";
        when(mockStorage.get(BlobId.of("llm-council-conversations", fileName))).thenReturn(null);

        SavedSession result = storageService.loadSessionGcs(fileName);

        assertNull(result);
    }

    @Test
    void loadSessionGcs_blobExistsReturnsFalse_returnsNull() {
        String fileName = "deleted-topic/2024-01-01T10-00-00Z.json";
        Blob mockBlob = mock(Blob.class);
        when(mockBlob.exists()).thenReturn(false);
        when(mockStorage.get(BlobId.of("llm-council-conversations", fileName))).thenReturn(mockBlob);

        SavedSession result = storageService.loadSessionGcs(fileName);

        assertNull(result);
    }

    @Test
    void loadSessionGcs_invalidFilenameFormat_throwsException() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            storageService.loadSessionGcs("invalid-filename.json");
        });

        assertEquals("Invalid GCS filename format", thrown.getMessage());
        verifyNoInteractions(mockStorage);
    }

    @Test
    void loadSessionGcs_pathTraversalAttempt_throwsException() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            storageService.loadSessionGcs("../etc/passwd");
        });

        assertEquals("Invalid GCS filename: path traversal not allowed", thrown.getMessage());
        verifyNoInteractions(mockStorage);
    }

    @Test
    void loadSessionGcs_absolutePath_throwsException() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            storageService.loadSessionGcs("/root/secret.json");
        });

        assertEquals("Invalid GCS filename: path traversal not allowed", thrown.getMessage());
        verifyNoInteractions(mockStorage);
    }

    @Test
    void loadSessionGcs_nullFilename_throwsException() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            storageService.loadSessionGcs(null);
        });

        assertEquals("Filename cannot be null or empty", thrown.getMessage());
        verifyNoInteractions(mockStorage);
    }

    @Test
    void loadSessionGcs_storageException_throwsRuntimeException() {
        String fileName = "error-topic/2024-01-01T10-00-00Z.json";
        when(mockStorage.get(BlobId.of("llm-council-conversations", fileName)))
            .thenThrow(new StorageException(500, "Internal Server Error"));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            storageService.loadSessionGcs(fileName);
        });

        assertEquals("Failed to load session from GCS", thrown.getMessage());
        assertTrue(thrown.getCause() instanceof StorageException);
    }
}
