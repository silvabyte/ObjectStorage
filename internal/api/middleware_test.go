package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func testMiddleware() func(http.Handler) http.Handler {
	return AuthMiddleware("test-api-key")
}

func TestValidRequest(t *testing.T) {
	handler := testMiddleware()(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tenant, ok := TenantFromContext(r.Context())
		if !ok {
			t.Error("tenant not in context")
		}
		if tenant.TenantID != "my-tenant" {
			t.Errorf("expected tenant my-tenant, got %s", tenant.TenantID)
		}
		if tenant.UserID != "my-user" {
			t.Errorf("expected user my-user, got %s", tenant.UserID)
		}
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/files/list", nil)
	req.Header.Set("x-api-key", "test-api-key")
	req.Header.Set("x-tenant-id", "my-tenant")
	req.Header.Set("x-user-id", "my-user")

	rr := httptest.NewRecorder()
	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", rr.Code)
	}
}

func TestMissingAPIKey(t *testing.T) {
	handler := testMiddleware()(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("should not reach handler")
	}))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/files/list", nil)
	rr := httptest.NewRecorder()
	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", rr.Code)
	}

	var body map[string]string
	json.NewDecoder(rr.Body).Decode(&body)
	if body["message"] != "Invalid API Key" {
		t.Errorf("unexpected message: %s", body["message"])
	}
}

func TestWrongAPIKey(t *testing.T) {
	handler := testMiddleware()(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("should not reach handler")
	}))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/files/list", nil)
	req.Header.Set("x-api-key", "wrong-key")
	rr := httptest.NewRecorder()
	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", rr.Code)
	}
}

func TestMissingTenantID(t *testing.T) {
	handler := testMiddleware()(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("should not reach handler")
	}))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/files/list", nil)
	req.Header.Set("x-api-key", "test-api-key")
	req.Header.Set("x-user-id", "my-user")
	rr := httptest.NewRecorder()
	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", rr.Code)
	}
}

func TestMissingUserID(t *testing.T) {
	handler := testMiddleware()(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("should not reach handler")
	}))

	req := httptest.NewRequest(http.MethodGet, "/api/v1/files/list", nil)
	req.Header.Set("x-api-key", "test-api-key")
	req.Header.Set("x-tenant-id", "my-tenant")
	rr := httptest.NewRecorder()
	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusUnauthorized {
		t.Errorf("expected 401, got %d", rr.Code)
	}
}

func TestHealthEndpointsNoAuth(t *testing.T) {
	handler := testMiddleware()(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	paths := []string{"/", "/api/v1/ping", "/api/v1/health", "/openapi.json", "/docs", "/schemas/something"}
	for _, path := range paths {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		rr := httptest.NewRecorder()
		handler.ServeHTTP(rr, req)

		if rr.Code != http.StatusOK {
			t.Errorf("expected 200 for %s, got %d", path, rr.Code)
		}
	}
}
