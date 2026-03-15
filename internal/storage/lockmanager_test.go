package storage

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func testTenant() Tenant {
	return Tenant{TenantID: "test-tenant", UserID: "test-user"}
}

func TestLockAcquireAndRelease(t *testing.T) {
	dir := t.TempDir()
	lm := newLockManager(dir)
	tenant := testTenant()

	lc, err := lm.tryLock(tenant, "obj-1")
	if err != nil {
		t.Fatalf("tryLock failed: %v", err)
	}
	if lc.ProcessID != lm.processID {
		t.Errorf("processID mismatch: got %s, want %s", lc.ProcessID, lm.processID)
	}

	if err := lm.releaseLock(tenant, "obj-1"); err != nil {
		t.Fatalf("releaseLock failed: %v", err)
	}
}

func TestLockFileCreatedOnDisk(t *testing.T) {
	dir := t.TempDir()
	lm := newLockManager(dir)
	tenant := testTenant()

	_, err := lm.tryLock(tenant, "obj-1")
	if err != nil {
		t.Fatalf("tryLock failed: %v", err)
	}

	lockFile := filepath.Join(dir, tenant.TenantID, tenant.UserID, "obj-1.lock")
	data, err := os.ReadFile(lockFile)
	if err != nil {
		t.Fatalf("lock file not found: %v", err)
	}

	var lc lockContext
	if err := json.Unmarshal(data, &lc); err != nil {
		t.Fatalf("invalid lock JSON: %v", err)
	}
	if lc.ProcessID != lm.processID {
		t.Errorf("processID mismatch in lock file")
	}
	if lc.TimeoutSeconds != 30 {
		t.Errorf("expected timeout 30, got %d", lc.TimeoutSeconds)
	}
}

func TestLockFileRemovedAfterRelease(t *testing.T) {
	dir := t.TempDir()
	lm := newLockManager(dir)
	tenant := testTenant()

	_, _ = lm.tryLock(tenant, "obj-1")
	_ = lm.releaseLock(tenant, "obj-1")

	lockFile := filepath.Join(dir, tenant.TenantID, tenant.UserID, "obj-1.lock")
	if _, err := os.Stat(lockFile); !os.IsNotExist(err) {
		t.Error("lock file should be removed after release")
	}
}

func TestDoubleLockFails(t *testing.T) {
	dir := t.TempDir()
	lm := newLockManager(dir)
	tenant := testTenant()

	_, err := lm.tryLock(tenant, "obj-1")
	if err != nil {
		t.Fatalf("first tryLock failed: %v", err)
	}

	_, err = lm.tryLock(tenant, "obj-1")
	if err == nil {
		t.Error("second tryLock should fail")
	}

	_ = lm.releaseLock(tenant, "obj-1")
}

func TestCannotReleaseLockFromDifferentProcess(t *testing.T) {
	dir := t.TempDir()
	lm1 := newLockManager(dir)
	lm2 := newLockManager(dir)
	tenant := testTenant()

	_, _ = lm1.tryLock(tenant, "obj-1")

	err := lm2.releaseLock(tenant, "obj-1")
	if err == nil {
		t.Error("release from different process should fail")
	}

	_ = lm1.releaseLock(tenant, "obj-1")
}

func TestCannotReleaseNonexistentLock(t *testing.T) {
	dir := t.TempDir()
	lm := newLockManager(dir)
	tenant := testTenant()

	err := lm.releaseLock(tenant, "nonexistent")
	if err == nil {
		t.Error("release of nonexistent lock should fail")
	}
}

func TestSelfHealingExpiredLock(t *testing.T) {
	dir := t.TempDir()
	lm := newLockManager(dir)
	tenant := testTenant()

	// Create an expired lock file manually
	lockDir := filepath.Join(dir, tenant.TenantID, tenant.UserID)
	os.MkdirAll(lockDir, 0755)
	lockFile := filepath.Join(lockDir, "obj-1.lock")

	expired := lockContext{
		ProcessID:      "old-process",
		CreatedAt:      time.Now().Add(-40 * time.Second),
		TimeoutSeconds: 30,
	}
	data, _ := json.Marshal(expired)
	os.WriteFile(lockFile, data, 0644)

	// Should succeed because old lock is expired
	lc, err := lm.tryLock(tenant, "obj-1")
	if err != nil {
		t.Fatalf("should acquire lock over expired one: %v", err)
	}
	if lc.ProcessID != lm.processID {
		t.Error("new lock should have current processID")
	}

	_ = lm.releaseLock(tenant, "obj-1")
}

func TestSelfHealingCorruptedLock(t *testing.T) {
	dir := t.TempDir()
	lm := newLockManager(dir)
	tenant := testTenant()

	lockDir := filepath.Join(dir, tenant.TenantID, tenant.UserID)
	os.MkdirAll(lockDir, 0755)
	lockFile := filepath.Join(lockDir, "obj-1.lock")
	os.WriteFile(lockFile, []byte("not valid json!!!"), 0644)

	lc, err := lm.tryLock(tenant, "obj-1")
	if err != nil {
		t.Fatalf("should acquire lock over corrupted one: %v", err)
	}
	if lc.ProcessID != lm.processID {
		t.Error("new lock should have current processID")
	}

	_ = lm.releaseLock(tenant, "obj-1")
}

func TestActiveLockFromAnotherProcessNotRemoved(t *testing.T) {
	dir := t.TempDir()
	lm := newLockManager(dir)
	tenant := testTenant()

	// Create an active lock from another process
	lockDir := filepath.Join(dir, tenant.TenantID, tenant.UserID)
	os.MkdirAll(lockDir, 0755)
	lockFile := filepath.Join(lockDir, "obj-1.lock")

	active := lockContext{
		ProcessID:      "other-process",
		CreatedAt:      time.Now(),
		TimeoutSeconds: 30,
	}
	data, _ := json.Marshal(active)
	os.WriteFile(lockFile, data, 0644)

	_, err := lm.tryLock(tenant, "obj-1")
	if err == nil {
		t.Error("should not acquire lock held by active process")
	}
}

func TestWithLockExecutesAndReleases(t *testing.T) {
	dir := t.TempDir()
	lm := newLockManager(dir)
	tenant := testTenant()

	executed := false
	err := lm.withLock(tenant, "obj-1", func() error {
		executed = true
		return nil
	})
	if err != nil {
		t.Fatalf("withLock failed: %v", err)
	}
	if !executed {
		t.Error("operation was not executed")
	}

	// Lock should be released — should be able to acquire again
	_, err = lm.tryLock(tenant, "obj-1")
	if err != nil {
		t.Errorf("lock should be released after withLock: %v", err)
	}
	_ = lm.releaseLock(tenant, "obj-1")
}

func TestWithLockReleasesOnError(t *testing.T) {
	dir := t.TempDir()
	lm := newLockManager(dir)
	tenant := testTenant()

	err := lm.withLock(tenant, "obj-1", func() error {
		return fmt.Errorf("operation failed")
	})
	if err == nil {
		t.Error("withLock should return error from operation")
	}

	// Lock should still be released
	_, err = lm.tryLock(tenant, "obj-1")
	if err != nil {
		t.Errorf("lock should be released even after error: %v", err)
	}
	_ = lm.releaseLock(tenant, "obj-1")
}

func TestWithLockDoesNotExecuteIfLockFails(t *testing.T) {
	dir := t.TempDir()
	lm1 := newLockManager(dir)
	lm2 := newLockManager(dir)
	tenant := testTenant()

	_, _ = lm1.tryLock(tenant, "obj-1")

	executed := false
	err := lm2.withLock(tenant, "obj-1", func() error {
		executed = true
		return nil
	})
	if err == nil {
		t.Error("withLock should fail when lock is held")
	}
	if executed {
		t.Error("operation should not execute when lock fails")
	}

	_ = lm1.releaseLock(tenant, "obj-1")
}

func TestConcurrentLockAttempts(t *testing.T) {
	dir := t.TempDir()
	tenant := testTenant()

	var successes atomic.Int32
	var wg sync.WaitGroup

	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			lm := newLockManager(dir)
			err := lm.withLock(tenant, "obj-1", func() error {
				successes.Add(1)
				time.Sleep(5 * time.Millisecond)
				return nil
			})
			_ = err
		}()
	}

	wg.Wait()

	if successes.Load() < 1 {
		t.Error("at least one goroutine should succeed")
	}

	// No stale locks after all goroutines complete
	lockFile := filepath.Join(dir, tenant.TenantID, tenant.UserID, "obj-1.lock")
	if _, err := os.Stat(lockFile); !os.IsNotExist(err) {
		t.Error("no stale lock files should remain")
	}
}
