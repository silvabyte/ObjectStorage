package storage

import (
	crypto_rand "crypto/rand"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"time"
)

type lockContext struct {
	ProcessID      string    `json:"processId"`
	CreatedAt      time.Time `json:"createdAt"`
	TimeoutSeconds int       `json:"timeoutSeconds"`
}

type lockManager struct {
	basePath  string
	processID string
}

func newLockManager(basePath string) *lockManager {
	return &lockManager{
		basePath:  basePath,
		processID: newUUID(),
	}
}

func newUUID() string {
	b := make([]byte, 16)
	_, _ = crypto_rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}

func (lm *lockManager) lockPath(t Tenant, objectID string) string {
	return filepath.Join(lm.basePath, t.TenantID, t.UserID, objectID+".lock")
}

func (lm *lockManager) tryLock(t Tenant, objectID string) (*lockContext, error) {
	lockFile := lm.lockPath(t, objectID)
	lockDir := filepath.Dir(lockFile)

	if err := os.MkdirAll(lockDir, 0755); err != nil {
		return nil, fmt.Errorf("create lock dir: %w", err)
	}

	// Check if lock file already exists
	if data, err := os.ReadFile(lockFile); err == nil {
		var existing lockContext
		if err := json.Unmarshal(data, &existing); err != nil {
			// Corrupted lock file — self-healing
			slog.Warn("removing corrupted lock file", "path", lockFile)
			os.Remove(lockFile)
		} else {
			expiry := existing.CreatedAt.Add(time.Duration(existing.TimeoutSeconds) * time.Second)
			if time.Now().After(expiry) {
				// Expired lock — self-healing
				slog.Warn("removing expired lock file", "path", lockFile, "expired", expiry)
				os.Remove(lockFile)
			} else {
				return nil, fmt.Errorf("lock held by process %s", existing.ProcessID)
			}
		}
	}

	// Create lock file with O_EXCL for atomic creation
	lc := lockContext{
		ProcessID:      lm.processID,
		CreatedAt:      time.Now(),
		TimeoutSeconds: 30,
	}

	f, err := os.OpenFile(lockFile, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0644)
	if err != nil {
		if os.IsExist(err) {
			return nil, fmt.Errorf("lock held (race condition)")
		}
		return nil, fmt.Errorf("create lock file: %w", err)
	}
	defer f.Close()

	if err := json.NewEncoder(f).Encode(lc); err != nil {
		os.Remove(lockFile)
		return nil, fmt.Errorf("write lock file: %w", err)
	}

	return &lc, nil
}

func (lm *lockManager) releaseLock(t Tenant, objectID string) error {
	lockFile := lm.lockPath(t, objectID)

	data, err := os.ReadFile(lockFile)
	if err != nil {
		return fmt.Errorf("read lock file: %w", err)
	}

	var lc lockContext
	if err := json.Unmarshal(data, &lc); err != nil {
		return fmt.Errorf("parse lock file: %w", err)
	}

	if lc.ProcessID != lm.processID {
		return fmt.Errorf("lock owned by different process %s", lc.ProcessID)
	}

	return os.Remove(lockFile)
}

func (lm *lockManager) withLock(t Tenant, objectID string, fn func() error) error {
	_, err := lm.tryLock(t, objectID)
	if err != nil {
		return err
	}

	defer func() {
		if err := lm.releaseLock(t, objectID); err != nil {
			slog.Error("failed to release lock", "error", err, "objectID", objectID)
		}
	}()

	return fn()
}
