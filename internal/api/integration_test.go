package api_test

import (
	"bytes"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/danielgtaylor/huma/v2"
	"github.com/danielgtaylor/huma/v2/adapters/humachi"
	"github.com/go-chi/chi/v5"
	"github.com/silvabyte/objectstorage/internal/api"
	"github.com/silvabyte/objectstorage/internal/storage"
)

func setupTestServer(t *testing.T) *httptest.Server {
	t.Helper()
	dir := t.TempDir()
	store := storage.NewFileStore(dir)

	router := chi.NewMux()
	router.Use(api.AuthMiddleware("test-api-key"))

	humaAPI := humachi.New(router, huma.DefaultConfig("ObjectStorage API", "1.0.0"))
	api.RegisterRoutes(humaAPI, router, store)

	return httptest.NewServer(router)
}

func authHeaders(req *http.Request) {
	req.Header.Set("x-api-key", "test-api-key")
	req.Header.Set("x-tenant-id", "test-tenant")
	req.Header.Set("x-user-id", "test-user")
}

type fileResponse struct {
	File   storage.StoredObject `json:"file"`
	Status string               `json:"status"`
}

type listResponse struct {
	Files  []storage.StoredObject `json:"files"`
	Status string                 `json:"status"`
}

func uploadFile(t *testing.T, server *httptest.Server, content, fileName string) fileResponse {
	t.Helper()
	req, _ := http.NewRequest("POST", server.URL+"/api/v1/files", bytes.NewReader([]byte(content)))
	authHeaders(req)
	req.Header.Set("x-file-name", fileName)
	req.Header.Set("x-mimetype", "text/plain")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("upload request failed: %v", err)
	}
	defer resp.Body.Close()

	var result fileResponse
	json.NewDecoder(resp.Body).Decode(&result)
	return result
}

func TestUploadStream(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	req, _ := http.NewRequest("POST", server.URL+"/api/v1/files", bytes.NewReader([]byte("hello world")))
	authHeaders(req)
	req.Header.Set("x-file-name", "test.txt")
	req.Header.Set("x-mimetype", "text/plain")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusCreated {
		body, _ := io.ReadAll(resp.Body)
		t.Fatalf("expected 201, got %d: %s", resp.StatusCode, string(body))
	}

	var result fileResponse
	json.NewDecoder(resp.Body).Decode(&result)

	if result.File.ObjectID == "" {
		t.Error("objectId should not be empty")
	}
	if result.File.FileName != "test.txt" {
		t.Errorf("expected fileName test.txt, got %s", result.File.FileName)
	}
	if result.File.Checksum == "" {
		t.Error("checksum should not be empty")
	}
	if result.File.Size <= 0 {
		t.Error("size should be > 0")
	}
}

func TestUploadDuplicate(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	first := uploadFile(t, server, "duplicate content", "first.txt")

	req, _ := http.NewRequest("POST", server.URL+"/api/v1/files", bytes.NewReader([]byte("duplicate content")))
	authHeaders(req)
	req.Header.Set("x-file-name", "second.txt")
	req.Header.Set("x-mimetype", "text/plain")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Errorf("expected 200 for duplicate, got %d", resp.StatusCode)
	}

	var second fileResponse
	json.NewDecoder(resp.Body).Decode(&second)

	if second.File.ObjectID != first.File.ObjectID {
		t.Errorf("duplicate should have same objectId: %s vs %s", first.File.ObjectID, second.File.ObjectID)
	}
}

func TestListFiles(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	uploadFile(t, server, "file one", "one.txt")

	req, _ := http.NewRequest("GET", server.URL+"/api/v1/files/list", nil)
	authHeaders(req)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	var result listResponse
	json.NewDecoder(resp.Body).Decode(&result)

	if len(result.Files) < 1 {
		t.Error("expected at least 1 file in list")
	}
}

func TestGetMetadata(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	uploaded := uploadFile(t, server, "metadata test", "meta.txt")

	req, _ := http.NewRequest("GET", server.URL+"/api/v1/files/metadata/"+uploaded.File.ObjectID, nil)
	authHeaders(req)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	var result fileResponse
	json.NewDecoder(resp.Body).Decode(&result)

	if result.File.ObjectID != uploaded.File.ObjectID {
		t.Error("objectId mismatch")
	}
	if result.File.FileName != "meta.txt" {
		t.Error("fileName mismatch")
	}
}

func TestDownload(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	content := "download me"
	uploaded := uploadFile(t, server, content, "download.txt")

	req, _ := http.NewRequest("GET", server.URL+"/api/v1/files/"+uploaded.File.ObjectID, nil)
	authHeaders(req)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	body, _ := io.ReadAll(resp.Body)
	if string(body) != content {
		t.Errorf("content mismatch: got %q, want %q", string(body), content)
	}
}

func TestChecksumLookup(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	uploaded := uploadFile(t, server, "checksum test", "checksum.txt")

	req, _ := http.NewRequest("GET", server.URL+"/api/v1/files/checksum/"+uploaded.File.Checksum, nil)
	authHeaders(req)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	var result fileResponse
	json.NewDecoder(resp.Body).Decode(&result)

	if result.File.ObjectID != uploaded.File.ObjectID {
		t.Error("objectId mismatch on checksum lookup")
	}
}

func TestDeleteFile(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	uploaded := uploadFile(t, server, "delete me", "delete.txt")

	req, _ := http.NewRequest("DELETE", server.URL+"/api/v1/files/"+uploaded.File.ObjectID, nil)
	authHeaders(req)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	// Verify get returns 404
	req, _ = http.NewRequest("GET", server.URL+"/api/v1/files/metadata/"+uploaded.File.ObjectID, nil)
	authHeaders(req)

	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("expected 404 after delete, got %d", resp.StatusCode)
	}
}

func TestUploadForm(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	var buf bytes.Buffer
	writer := multipart.NewWriter(&buf)
	part, _ := writer.CreateFormFile("file", "form-upload.txt")
	part.Write([]byte("form upload content"))
	writer.Close()

	req, _ := http.NewRequest("POST", server.URL+"/api/v1/files/form", &buf)
	authHeaders(req)
	req.Header.Set("Content-Type", writer.FormDataContentType())

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusCreated {
		body, _ := io.ReadAll(resp.Body)
		t.Fatalf("expected 201, got %d: %s", resp.StatusCode, string(body))
	}

	var result fileResponse
	json.NewDecoder(resp.Body).Decode(&result)

	if result.File.ObjectID == "" {
		t.Error("objectId should not be empty")
	}
}

func TestNDJSONFlow(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	// Upload initial file
	uploaded := uploadFile(t, server, "{\"line\":1}\n", "data.ndjson")
	originalChecksum := uploaded.File.Checksum

	// Append data
	appendData := []byte("{\"line\":2}\n")
	req, _ := http.NewRequest("POST", server.URL+"/api/v1/files/"+uploaded.File.ObjectID+"/ndjson/append/stream", bytes.NewReader(appendData))
	authHeaders(req)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("append request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		t.Fatalf("expected 200, got %d: %s", resp.StatusCode, string(body))
	}

	var appendResult fileResponse
	json.NewDecoder(resp.Body).Decode(&appendResult)

	if appendResult.File.Size <= uploaded.File.Size {
		t.Error("size should increase after append")
	}
	if appendResult.File.Checksum == originalChecksum {
		t.Error("checksum should change after append")
	}

	// Download and verify
	req, _ = http.NewRequest("GET", server.URL+"/api/v1/files/"+uploaded.File.ObjectID+"/ndjson/items/stream", nil)
	authHeaders(req)

	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("stream request failed: %v", err)
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	expected := "{\"line\":1}\n{\"line\":2}\n"
	if string(body) != expected {
		t.Errorf("content mismatch: got %q, want %q", string(body), expected)
	}
}

func TestAuthMissingAPIKey(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	req, _ := http.NewRequest("GET", server.URL+"/api/v1/files/list", nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", resp.StatusCode)
	}
}

func TestAuthWrongAPIKey(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	req, _ := http.NewRequest("GET", server.URL+"/api/v1/files/list", nil)
	req.Header.Set("x-api-key", "wrong-key")
	req.Header.Set("x-tenant-id", "t")
	req.Header.Set("x-user-id", "u")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", resp.StatusCode)
	}
}

func TestAuthMissingTenantUser(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	req, _ := http.NewRequest("GET", server.URL+"/api/v1/files/list", nil)
	req.Header.Set("x-api-key", "test-api-key")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", resp.StatusCode)
	}
}

func TestHealthNoAuth(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	paths := []string{"/", "/api/v1/ping", "/api/v1/health"}
	for _, path := range paths {
		resp, err := http.Get(server.URL + path)
		if err != nil {
			t.Fatalf("request to %s failed: %v", path, err)
		}
		resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			t.Errorf("expected 200 for %s, got %d", path, resp.StatusCode)
		}
	}
}

func TestOpenAPI(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	resp, err := http.Get(server.URL + "/openapi.json")
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	var result map[string]interface{}
	json.NewDecoder(resp.Body).Decode(&result)

	if _, ok := result["openapi"]; !ok {
		t.Error("response should contain 'openapi' key")
	}
}

func TestGetNonExistent(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	req, _ := http.NewRequest("GET", server.URL+"/api/v1/files/metadata/00000000-0000-4000-8000-000000000000", nil)
	authHeaders(req)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("expected 404, got %d", resp.StatusCode)
	}
}

func TestDeleteNonExistent(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	req, _ := http.NewRequest("DELETE", server.URL+"/api/v1/files/00000000-0000-4000-8000-000000000000", nil)
	authHeaders(req)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("expected 404, got %d", resp.StatusCode)
	}
}

func TestInvalidUUID(t *testing.T) {
	server := setupTestServer(t)
	defer server.Close()

	req, _ := http.NewRequest("GET", server.URL+"/api/v1/files/metadata/not-a-uuid", nil)
	authHeaders(req)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusBadRequest {
		body, _ := io.ReadAll(resp.Body)
		t.Errorf("expected 400, got %d: %s", resp.StatusCode, string(body))
	}
}
