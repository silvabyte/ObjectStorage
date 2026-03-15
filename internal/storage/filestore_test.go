package storage

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestComputeChecksumEmptyFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "empty")
	os.WriteFile(path, []byte{}, 0644)

	checksum, err := computeChecksum(path)
	if err != nil {
		t.Fatalf("computeChecksum failed: %v", err)
	}

	expected := "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
	if checksum != expected {
		t.Errorf("expected %s, got %s", expected, checksum)
	}
}

func TestComputeChecksumConsistency(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "test")
	os.WriteFile(path, []byte("hello world"), 0644)

	c1, _ := computeChecksum(path)
	c2, _ := computeChecksum(path)

	if c1 != c2 {
		t.Errorf("checksums should match: %s vs %s", c1, c2)
	}
}

func TestUploadAndGet(t *testing.T) {
	dir := t.TempDir()
	fs := NewFileStore(dir)
	tenant := Tenant{TenantID: "t1", UserID: "u1"}

	obj, dup, err := fs.Upload(tenant, strings.NewReader("hello world"), "test.txt", "text/plain")
	if err != nil {
		t.Fatalf("Upload failed: %v", err)
	}
	if dup {
		t.Error("first upload should not be duplicate")
	}
	if obj.FileName != "test.txt" {
		t.Errorf("expected fileName test.txt, got %s", obj.FileName)
	}
	if obj.Size != 11 {
		t.Errorf("expected size 11, got %d", obj.Size)
	}
	if obj.Checksum == "" {
		t.Error("checksum should not be empty")
	}
	if obj.ObjectID == "" {
		t.Error("objectID should not be empty")
	}

	got, err := fs.Get(tenant, obj.ObjectID)
	if err != nil {
		t.Fatalf("Get failed: %v", err)
	}
	if got.ObjectID != obj.ObjectID {
		t.Errorf("objectID mismatch: got %s, want %s", got.ObjectID, obj.ObjectID)
	}
}

func TestUploadDedup(t *testing.T) {
	dir := t.TempDir()
	fs := NewFileStore(dir)
	tenant := Tenant{TenantID: "t1", UserID: "u1"}

	obj1, dup1, _ := fs.Upload(tenant, strings.NewReader("same content"), "file1.txt", "text/plain")
	obj2, dup2, _ := fs.Upload(tenant, strings.NewReader("same content"), "file2.txt", "text/plain")

	if dup1 {
		t.Error("first upload should not be duplicate")
	}
	if !dup2 {
		t.Error("second upload should be duplicate")
	}
	if obj1.ObjectID != obj2.ObjectID {
		t.Errorf("duplicate should return same objectID: %s vs %s", obj1.ObjectID, obj2.ObjectID)
	}
}

func TestUploadFormNoDedup(t *testing.T) {
	dir := t.TempDir()
	fs := NewFileStore(dir)
	tenant := Tenant{TenantID: "t1", UserID: "u1"}

	obj1, _ := fs.UploadForm(tenant, strings.NewReader("same content"), "file1.txt")
	obj2, _ := fs.UploadForm(tenant, strings.NewReader("same content"), "file2.txt")

	if obj1.ObjectID == obj2.ObjectID {
		t.Error("form uploads should produce different objectIDs")
	}
}

func TestList(t *testing.T) {
	dir := t.TempDir()
	fs := NewFileStore(dir)
	tenant := Tenant{TenantID: "t1", UserID: "u1"}

	fs.Upload(tenant, strings.NewReader("file 1"), "a.txt", "text/plain")
	fs.Upload(tenant, strings.NewReader("file 2"), "b.txt", "text/plain")

	files, err := fs.List(tenant)
	if err != nil {
		t.Fatalf("List failed: %v", err)
	}
	if len(files) != 2 {
		t.Errorf("expected 2 files, got %d", len(files))
	}
}

func TestListEmptyDir(t *testing.T) {
	dir := t.TempDir()
	fs := NewFileStore(dir)
	tenant := Tenant{TenantID: "nonexistent", UserID: "user"}

	files, err := fs.List(tenant)
	if err != nil {
		t.Fatalf("List failed: %v", err)
	}
	if len(files) != 0 {
		t.Errorf("expected 0 files, got %d", len(files))
	}
}

func TestDownload(t *testing.T) {
	dir := t.TempDir()
	fs := NewFileStore(dir)
	tenant := Tenant{TenantID: "t1", UserID: "u1"}

	content := "hello download"
	obj, _, _ := fs.Upload(tenant, strings.NewReader(content), "test.txt", "text/plain")

	f, meta, err := fs.Download(tenant, obj.ObjectID)
	if err != nil {
		t.Fatalf("Download failed: %v", err)
	}
	defer f.Close()

	if meta.ObjectID != obj.ObjectID {
		t.Errorf("metadata objectID mismatch")
	}

	var buf bytes.Buffer
	buf.ReadFrom(f)
	if buf.String() != content {
		t.Errorf("content mismatch: got %q, want %q", buf.String(), content)
	}
}

func TestDelete(t *testing.T) {
	dir := t.TempDir()
	fs := NewFileStore(dir)
	tenant := Tenant{TenantID: "t1", UserID: "u1"}

	obj, _, _ := fs.Upload(tenant, strings.NewReader("to delete"), "del.txt", "text/plain")

	deleted, err := fs.Delete(tenant, obj.ObjectID)
	if err != nil {
		t.Fatalf("Delete failed: %v", err)
	}
	if deleted.ObjectID != obj.ObjectID {
		t.Error("deleted object ID mismatch")
	}

	// Verify files removed from disk
	tenantDir := filepath.Join(dir, tenant.TenantID, tenant.UserID)
	if _, err := os.Stat(filepath.Join(tenantDir, obj.ObjectID)); !os.IsNotExist(err) {
		t.Error("content file should be removed")
	}
	if _, err := os.Stat(filepath.Join(tenantDir, obj.ObjectID+".json")); !os.IsNotExist(err) {
		t.Error("metadata file should be removed")
	}

	// Verify lookup.json cleaned
	lookup := fs.loadLookup(tenantDir)
	for _, v := range lookup {
		if v == obj.ObjectID {
			t.Error("lookup should not contain deleted objectID")
		}
	}
}

func TestLookupByChecksum(t *testing.T) {
	dir := t.TempDir()
	fs := NewFileStore(dir)
	tenant := Tenant{TenantID: "t1", UserID: "u1"}

	obj, _, _ := fs.Upload(tenant, strings.NewReader("lookup test"), "test.txt", "text/plain")

	found, err := fs.LookupByChecksum(tenant, obj.Checksum)
	if err != nil {
		t.Fatalf("LookupByChecksum failed: %v", err)
	}
	if found.ObjectID != obj.ObjectID {
		t.Errorf("objectID mismatch: got %s, want %s", found.ObjectID, obj.ObjectID)
	}
}

func TestLookupByChecksumNotFound(t *testing.T) {
	dir := t.TempDir()
	fs := NewFileStore(dir)
	tenant := Tenant{TenantID: "t1", UserID: "u1"}

	_, err := fs.LookupByChecksum(tenant, "nonexistent-checksum")
	if !errors.Is(err, ErrNotFound) {
		t.Errorf("expected ErrNotFound, got %v", err)
	}
}

func TestAppend(t *testing.T) {
	dir := t.TempDir()
	fs := NewFileStore(dir)
	tenant := Tenant{TenantID: "t1", UserID: "u1"}

	obj, _, _ := fs.Upload(tenant, strings.NewReader("line1\n"), "data.ndjson", "application/x-ndjson")
	originalChecksum := obj.Checksum
	originalSize := obj.Size

	updated, err := fs.Append(tenant, obj.ObjectID, []byte("line2\n"))
	if err != nil {
		t.Fatalf("Append failed: %v", err)
	}

	if updated.Size <= originalSize {
		t.Errorf("size should increase: got %d, was %d", updated.Size, originalSize)
	}
	if updated.Checksum == originalChecksum {
		t.Error("checksum should change after append")
	}
	if !updated.LastModified.After(obj.CreatedAt.Add(-time.Second)) {
		t.Error("lastModified should be updated")
	}

	// Verify content
	f, _, _ := fs.Download(tenant, obj.ObjectID)
	defer f.Close()
	var buf bytes.Buffer
	buf.ReadFrom(f)
	if buf.String() != "line1\nline2\n" {
		t.Errorf("content mismatch: got %q", buf.String())
	}
}

func TestAppendLocked(t *testing.T) {
	dir := t.TempDir()
	fs := NewFileStore(dir)
	tenant := Tenant{TenantID: "t1", UserID: "u1"}

	obj, _, _ := fs.Upload(tenant, strings.NewReader("data"), "test.txt", "text/plain")

	// Manually acquire a lock
	_, err := fs.lockMgr.tryLock(tenant, obj.ObjectID)
	if err != nil {
		t.Fatalf("manual lock failed: %v", err)
	}

	// Attempt append with a store that has a DIFFERENT lock manager (different processID)
	fs2 := NewFileStore(dir)
	_, err = fs2.Append(tenant, obj.ObjectID, []byte("more data"))
	if !errors.Is(err, ErrLocked) {
		t.Errorf("expected ErrLocked, got %v", err)
	}

	_ = fs.lockMgr.releaseLock(tenant, obj.ObjectID)
}

func TestLoadMetadataNilMap(t *testing.T) {
	dir := t.TempDir()

	// Write metadata with no Metadata field
	metaJSON := `{"objectId":"test","bucket":"b","fileName":"f.txt","size":0,"createdAt":"2024-01-01T00:00:00Z","lastModified":"2024-01-01T00:00:00Z"}`
	metaPath := filepath.Join(dir, "test.json")
	os.WriteFile(metaPath, []byte(metaJSON), 0644)

	obj, err := loadMetadata(metaPath)
	if err != nil {
		t.Fatalf("loadMetadata failed: %v", err)
	}
	if obj.Metadata == nil {
		t.Error("Metadata should not be nil after loadMetadata")
	}
}

func TestGetNotFound(t *testing.T) {
	dir := t.TempDir()
	fs := NewFileStore(dir)
	tenant := Tenant{TenantID: "t1", UserID: "u1"}

	_, err := fs.Get(tenant, "nonexistent-id")
	if !errors.Is(err, ErrNotFound) {
		t.Errorf("expected ErrNotFound, got %v", err)
	}
}

func TestDeleteNotFound(t *testing.T) {
	dir := t.TempDir()
	fs := NewFileStore(dir)
	tenant := Tenant{TenantID: "t1", UserID: "u1"}

	_, err := fs.Delete(tenant, "nonexistent-id")
	if !errors.Is(err, ErrNotFound) {
		t.Errorf("expected ErrNotFound, got %v", err)
	}
}
