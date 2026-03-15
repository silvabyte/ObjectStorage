package config

import (
	"os"
	"strconv"
	"strings"

	"github.com/joho/godotenv"
)

type Config struct {
	Host            string
	Port            int
	LogPretty       bool
	StorageBasePath string
	APIKey          string
}

func Load() *Config {
	_ = godotenv.Load()

	return &Config{
		Host:            getEnvOrDefault("OBJECT_STORAGE_HOST", "0.0.0.0"),
		Port:            getEnvIntOrDefault("OBJECT_STORAGE_PORT", 8080),
		LogPretty:       getEnvBoolOrDefault("LOG_PRETTY", false),
		StorageBasePath: getEnvOrDefault("STORAGE_BASE_PATH", "./bucket"),
		APIKey:          getEnvOrDefault("API_KEY", "test-api-key"),
	}
}

func getEnvOrDefault(key, defaultVal string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return defaultVal
}

func getEnvIntOrDefault(key string, defaultVal int) int {
	val := os.Getenv(key)
	if val == "" {
		return defaultVal
	}
	n, err := strconv.Atoi(val)
	if err != nil {
		return defaultVal
	}
	return n
}

func getEnvBoolOrDefault(key string, defaultVal bool) bool {
	val := os.Getenv(key)
	if val == "" {
		return defaultVal
	}
	switch strings.ToLower(val) {
	case "true", "1", "yes":
		return true
	case "false", "0", "no":
		return false
	default:
		return defaultVal
	}
}
