package main

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"github.com/danielgtaylor/huma/v2/adapters/humachi"
	"github.com/go-chi/chi/v5"
	"github.com/silvabyte/objectstorage/internal/api"
	"github.com/silvabyte/objectstorage/internal/config"
	"github.com/silvabyte/objectstorage/internal/storage"
)

func main() {
	cfg := config.Load()

	// Set up logging
	var handler slog.Handler
	if cfg.LogPretty {
		handler = slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelDebug})
	} else {
		handler = slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})
	}
	slog.SetDefault(slog.New(handler))

	// Create file store
	store := storage.NewFileStore(cfg.StorageBasePath)

	// Create chi router
	router := chi.NewMux()

	// Request logging middleware
	router.Use(func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			ww := &statusWriter{ResponseWriter: w, status: http.StatusOK}
			next.ServeHTTP(ww, r)
			slog.Info("request",
				"method", r.Method,
				"path", r.URL.Path,
				"status", ww.status,
				"duration", time.Since(start).String(),
			)
		})
	})

	// Auth middleware
	router.Use(api.AuthMiddleware(cfg.APIKey))

	// Create huma API
	humaAPI := humachi.New(router, huma.DefaultConfig("ObjectStorage API", "1.0.0"))

	// Register routes
	api.RegisterRoutes(humaAPI, router, store)

	// Start server
	addr := fmt.Sprintf("%s:%d", cfg.Host, cfg.Port)
	slog.Info("starting server", "addr", addr)
	if err := http.ListenAndServe(addr, router); err != nil {
		slog.Error("server error", "error", err)
		os.Exit(1)
	}
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}
