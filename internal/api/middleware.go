package api

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"

	"github.com/silvabyte/objectstorage/internal/storage"
)

type contextKey string

const tenantKey contextKey = "tenant"

// TenantFromContext extracts the Tenant from context.
func TenantFromContext(ctx context.Context) (storage.Tenant, bool) {
	t, ok := ctx.Value(tenantKey).(storage.Tenant)
	return t, ok
}

// mustTenant extracts the Tenant from context without checking.
// Safe because middleware guarantees Tenant is present for authenticated routes.
func mustTenant(ctx context.Context) storage.Tenant {
	t, _ := TenantFromContext(ctx)
	return t
}

// AuthMiddleware creates chi middleware that validates API key and tenant headers.
func AuthMiddleware(apiKey string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			// Skip auth for health/public endpoints
			if isPublicPath(r.Method, r.URL.Path) {
				next.ServeHTTP(w, r)
				return
			}

			// Check API key
			key := r.Header.Get("x-api-key")
			if key == "" || key != apiKey {
				writeAuthError(w, "Invalid API Key", "unauthorized")
				return
			}

			// Check tenant headers
			tenantID := r.Header.Get("x-tenant-id")
			userID := r.Header.Get("x-user-id")
			if tenantID == "" || userID == "" {
				writeAuthError(w, "Missing required headers: x-tenant-id, x-user-id", "unauthorized")
				return
			}

			tenant := storage.Tenant{TenantID: tenantID, UserID: userID}
			ctx := context.WithValue(r.Context(), tenantKey, tenant)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func isPublicPath(method, path string) bool {
	if method != http.MethodGet {
		return false
	}

	switch path {
	case "/", "/api/v1/ping", "/api/v1/health", "/openapi.json", "/docs":
		return true
	}

	if strings.HasPrefix(path, "/schemas/") {
		return true
	}

	return false
}

func writeAuthError(w http.ResponseWriter, message, status string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusUnauthorized)
	json.NewEncoder(w).Encode(map[string]string{
		"message": message,
		"status":  status,
	})
}
