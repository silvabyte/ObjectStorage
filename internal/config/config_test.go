package config

import (
	"os"
	"testing"
)

func TestDefaultValues(t *testing.T) {
	// Clear all relevant env vars
	for _, key := range []string{"OBJECT_STORAGE_HOST", "OBJECT_STORAGE_PORT", "LOG_PRETTY", "STORAGE_BASE_PATH", "API_KEY"} {
		os.Unsetenv(key)
	}

	cfg := Load()

	if cfg.Host != "0.0.0.0" {
		t.Errorf("expected host 0.0.0.0, got %s", cfg.Host)
	}
	if cfg.Port != 8080 {
		t.Errorf("expected port 8080, got %d", cfg.Port)
	}
	if cfg.LogPretty != false {
		t.Errorf("expected LogPretty false, got %v", cfg.LogPretty)
	}
	if cfg.StorageBasePath != "./bucket" {
		t.Errorf("expected StorageBasePath ./bucket, got %s", cfg.StorageBasePath)
	}
	if cfg.APIKey != "test-api-key" {
		t.Errorf("expected APIKey test-api-key, got %s", cfg.APIKey)
	}
}

func TestEnvOverrides(t *testing.T) {
	t.Setenv("OBJECT_STORAGE_HOST", "127.0.0.1")
	t.Setenv("OBJECT_STORAGE_PORT", "9090")
	t.Setenv("LOG_PRETTY", "true")
	t.Setenv("STORAGE_BASE_PATH", "/data/files")
	t.Setenv("API_KEY", "my-secret-key")

	cfg := Load()

	if cfg.Host != "127.0.0.1" {
		t.Errorf("expected host 127.0.0.1, got %s", cfg.Host)
	}
	if cfg.Port != 9090 {
		t.Errorf("expected port 9090, got %d", cfg.Port)
	}
	if cfg.LogPretty != true {
		t.Errorf("expected LogPretty true, got %v", cfg.LogPretty)
	}
	if cfg.StorageBasePath != "/data/files" {
		t.Errorf("expected StorageBasePath /data/files, got %s", cfg.StorageBasePath)
	}
	if cfg.APIKey != "my-secret-key" {
		t.Errorf("expected APIKey my-secret-key, got %s", cfg.APIKey)
	}
}

func TestBoolParsing(t *testing.T) {
	trueValues := []string{"true", "1", "yes", "TRUE", "Yes"}
	falseValues := []string{"false", "0", "no", "FALSE", "No"}

	for _, val := range trueValues {
		t.Setenv("LOG_PRETTY", val)
		cfg := Load()
		if !cfg.LogPretty {
			t.Errorf("expected LogPretty true for %q, got false", val)
		}
	}

	for _, val := range falseValues {
		t.Setenv("LOG_PRETTY", val)
		cfg := Load()
		if cfg.LogPretty {
			t.Errorf("expected LogPretty false for %q, got true", val)
		}
	}
}

func TestIntParsingFallback(t *testing.T) {
	t.Setenv("OBJECT_STORAGE_PORT", "not-a-number")
	cfg := Load()
	if cfg.Port != 8080 {
		t.Errorf("expected port 8080 on invalid int, got %d", cfg.Port)
	}
}
