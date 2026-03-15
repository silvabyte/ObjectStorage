package storage

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var uuidRegex = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// IsValidUUID checks if a string is a valid UUID v4 format.
func IsValidUUID(s string) bool {
	return uuidRegex.MatchString(s)
}

// FileStore provides file storage operations backed by the local filesystem.
type FileStore struct {
	basePath string
	lockMgr  *lockManager
}

// NewFileStore creates a new FileStore rooted at basePath.
func NewFileStore(basePath string) *FileStore {
	lockBasePath := filepath.Join(basePath, ".locks")
	_ = os.MkdirAll(lockBasePath, 0755)
	return &FileStore{
		basePath: basePath,
		lockMgr:  newLockManager(lockBasePath),
	}
}

func (fs *FileStore) tenantDir(t Tenant) string {
	return filepath.Join(fs.basePath, t.TenantID, t.UserID)
}

// loadMetadata reads and parses a StoredObject JSON file, ensuring Metadata is never nil.
func loadMetadata(path string) (*StoredObject, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var obj StoredObject
	if err := json.Unmarshal(data, &obj); err != nil {
		return nil, err
	}
	if obj.Metadata == nil {
		obj.Metadata = make(map[string]string)
	}
	return &obj, nil
}

func computeChecksum(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()

	h := sha256.New()
	buf := make([]byte, 8192)
	for {
		n, err := f.Read(buf)
		if n > 0 {
			h.Write(buf[:n])
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return "", err
		}
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func (fs *FileStore) loadLookup(dir string) map[string]string {
	lookupPath := filepath.Join(dir, "lookup.json")
	data, err := os.ReadFile(lookupPath)
	if err != nil {
		return make(map[string]string)
	}
	var lookup map[string]string
	if err := json.Unmarshal(data, &lookup); err != nil {
		return make(map[string]string)
	}
	return lookup
}

func (fs *FileStore) saveLookup(dir string, lookup map[string]string) error {
	lookupPath := filepath.Join(dir, "lookup.json")
	data, err := json.Marshal(lookup)
	if err != nil {
		return err
	}
	return os.WriteFile(lookupPath, data, 0644)
}

// Upload stores a file from an io.Reader with deduplication.
// Returns (object, duplicate, error). duplicate=true means the content already existed.
func (fs *FileStore) Upload(t Tenant, r io.Reader, fileName string, mimeType string) (*StoredObject, bool, error) {
	dir := fs.tenantDir(t)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, false, fmt.Errorf("create tenant dir: %w", err)
	}

	// Write to temp file in same directory for atomic rename
	tmpFile, err := os.CreateTemp(dir, "upload-*.tmp")
	if err != nil {
		return nil, false, fmt.Errorf("create temp file: %w", err)
	}
	tmpPath := tmpFile.Name()
	defer os.Remove(tmpPath)

	if _, err := io.Copy(tmpFile, r); err != nil {
		tmpFile.Close()
		return nil, false, fmt.Errorf("write temp file: %w", err)
	}
	tmpFile.Close()

	checksum, err := computeChecksum(tmpPath)
	if err != nil {
		return nil, false, fmt.Errorf("compute checksum: %w", err)
	}

	lookup := fs.loadLookup(dir)

	// Check for duplicate
	if existingID, ok := lookup[checksum]; ok {
		metaPath := filepath.Join(dir, existingID+".json")
		existing, err := loadMetadata(metaPath)
		if err == nil {
			return existing, true, nil
		}
		// If metadata is missing/corrupt, fall through and re-upload
		delete(lookup, checksum)
	}

	// New file
	objectID := newUUID()
	contentPath := filepath.Join(dir, objectID)
	if err := os.Rename(tmpPath, contentPath); err != nil {
		return nil, false, fmt.Errorf("rename temp to content: %w", err)
	}

	fi, err := os.Stat(contentPath)
	if err != nil {
		return nil, false, fmt.Errorf("stat content: %w", err)
	}

	now := time.Now()
	obj := &StoredObject{
		ObjectID:     objectID,
		Bucket:       t.TenantID,
		FileName:     fileName,
		Size:         fi.Size(),
		MimeType:     mimeType,
		ContentType:  mimeType,
		CreatedAt:    now,
		LastModified: now,
		Checksum:     checksum,
		ETag:         checksum,
		Metadata:     make(map[string]string),
	}

	metaData, err := json.Marshal(obj)
	if err != nil {
		return nil, false, fmt.Errorf("marshal metadata: %w", err)
	}
	if err := os.WriteFile(filepath.Join(dir, objectID+".json"), metaData, 0644); err != nil {
		return nil, false, fmt.Errorf("write metadata: %w", err)
	}

	lookup[checksum] = objectID
	if err := fs.saveLookup(dir, lookup); err != nil {
		slog.Error("failed to save lookup", "error", err)
	}

	return obj, false, nil
}

// UploadForm stores a file from a multipart form upload without deduplication.
func (fs *FileStore) UploadForm(t Tenant, r io.Reader, fileName string) (*StoredObject, error) {
	dir := fs.tenantDir(t)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, fmt.Errorf("create tenant dir: %w", err)
	}

	objectID := newUUID()
	contentPath := filepath.Join(dir, objectID)

	f, err := os.Create(contentPath)
	if err != nil {
		return nil, fmt.Errorf("create content file: %w", err)
	}

	size, err := io.Copy(f, r)
	if err != nil {
		f.Close()
		os.Remove(contentPath)
		return nil, fmt.Errorf("write content: %w", err)
	}
	f.Close()

	now := time.Now()
	obj := &StoredObject{
		ObjectID:     objectID,
		Bucket:       t.TenantID,
		FileName:     fileName,
		Size:         size,
		CreatedAt:    now,
		LastModified: now,
		Metadata:     make(map[string]string),
	}

	metaData, err := json.Marshal(obj)
	if err != nil {
		return nil, fmt.Errorf("marshal metadata: %w", err)
	}
	if err := os.WriteFile(filepath.Join(dir, objectID+".json"), metaData, 0644); err != nil {
		return nil, fmt.Errorf("write metadata: %w", err)
	}

	return obj, nil
}

// Download returns an open file handle and metadata for the given object.
// Caller must close the returned file.
func (fs *FileStore) Download(t Tenant, objectID string) (*os.File, *StoredObject, error) {
	if !IsValidUUID(objectID) {
		return nil, nil, fmt.Errorf("invalid object ID: %s", objectID)
	}

	dir := fs.tenantDir(t)
	contentPath := filepath.Join(dir, objectID)

	f, err := os.Open(contentPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil, ErrNotFound
		}
		return nil, nil, err
	}

	metaPath := filepath.Join(dir, objectID+".json")
	obj, err := loadMetadata(metaPath)
	if err != nil {
		f.Close()
		if os.IsNotExist(err) {
			return nil, nil, ErrNotFound
		}
		return nil, nil, err
	}

	return f, obj, nil
}

// Get returns metadata for the given object.
func (fs *FileStore) Get(t Tenant, objectID string) (*StoredObject, error) {
	dir := fs.tenantDir(t)
	metaPath := filepath.Join(dir, objectID+".json")

	obj, err := loadMetadata(metaPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return obj, nil
}

// List returns all stored objects for the given tenant.
func (fs *FileStore) List(t Tenant) ([]StoredObject, error) {
	dir := fs.tenantDir(t)

	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return []StoredObject{}, nil
		}
		return nil, err
	}

	var objects []StoredObject
	for _, entry := range entries {
		name := entry.Name()
		if !strings.HasSuffix(name, ".json") || name == "lookup.json" {
			continue
		}
		metaPath := filepath.Join(dir, name)
		obj, err := loadMetadata(metaPath)
		if err != nil {
			slog.Warn("skipping unparseable metadata", "file", name, "error", err)
			continue
		}
		objects = append(objects, *obj)
	}

	if objects == nil {
		objects = []StoredObject{}
	}
	return objects, nil
}

// Delete removes the content and metadata files for the given object.
func (fs *FileStore) Delete(t Tenant, objectID string) (*StoredObject, error) {
	dir := fs.tenantDir(t)
	metaPath := filepath.Join(dir, objectID+".json")

	obj, err := loadMetadata(metaPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, ErrNotFound
		}
		return nil, err
	}

	contentPath := filepath.Join(dir, objectID)
	if err := os.Remove(contentPath); err != nil && !os.IsNotExist(err) {
		return nil, fmt.Errorf("remove content: %w", err)
	}
	if err := os.Remove(metaPath); err != nil && !os.IsNotExist(err) {
		return nil, fmt.Errorf("remove metadata: %w", err)
	}

	// Update lookup.json: remove entry where value == objectID
	lookup := fs.loadLookup(dir)
	for k, v := range lookup {
		if v == objectID {
			delete(lookup, k)
			break
		}
	}
	if err := fs.saveLookup(dir, lookup); err != nil {
		slog.Error("failed to save lookup after delete", "error", err)
	}

	return obj, nil
}

// LookupByChecksum finds an object by its SHA-256 checksum.
func (fs *FileStore) LookupByChecksum(t Tenant, checksum string) (*StoredObject, error) {
	dir := fs.tenantDir(t)
	lookup := fs.loadLookup(dir)

	objectID, ok := lookup[checksum]
	if !ok {
		return nil, ErrNotFound
	}

	metaPath := filepath.Join(dir, objectID+".json")
	return loadMetadata(metaPath)
}

// Append appends data to an existing file, updating its metadata.
func (fs *FileStore) Append(t Tenant, objectID string, data []byte) (*StoredObject, error) {
	dir := fs.tenantDir(t)
	var result *StoredObject

	err := fs.lockMgr.withLock(t, objectID, func() error {
		contentPath := filepath.Join(dir, objectID)
		metaPath := filepath.Join(dir, objectID+".json")

		// Verify files exist
		if _, err := os.Stat(contentPath); os.IsNotExist(err) {
			return ErrNotFound
		}
		if _, err := os.Stat(metaPath); os.IsNotExist(err) {
			return ErrNotFound
		}

		// Load current metadata to get old checksum
		obj, err := loadMetadata(metaPath)
		if err != nil {
			return err
		}
		oldChecksum := obj.Checksum

		// Append data
		f, err := os.OpenFile(contentPath, os.O_APPEND|os.O_WRONLY, 0644)
		if err != nil {
			return fmt.Errorf("open for append: %w", err)
		}
		if _, err := f.Write(data); err != nil {
			f.Close()
			return fmt.Errorf("append data: %w", err)
		}
		f.Close()

		// Recompute checksum
		newChecksum, err := computeChecksum(contentPath)
		if err != nil {
			return fmt.Errorf("recompute checksum: %w", err)
		}

		// Get new file size
		fi, err := os.Stat(contentPath)
		if err != nil {
			return fmt.Errorf("stat after append: %w", err)
		}

		// Update metadata
		obj.Size = fi.Size()
		obj.LastModified = time.Now()
		obj.Checksum = newChecksum
		obj.ETag = newChecksum

		metaData, err := json.Marshal(obj)
		if err != nil {
			return fmt.Errorf("marshal metadata: %w", err)
		}
		if err := os.WriteFile(metaPath, metaData, 0644); err != nil {
			return fmt.Errorf("write metadata: %w", err)
		}

		// Update lookup: remove old checksum, add new
		lookup := fs.loadLookup(dir)
		if oldChecksum != "" {
			delete(lookup, oldChecksum)
		}
		lookup[newChecksum] = objectID
		if err := fs.saveLookup(dir, lookup); err != nil {
			slog.Error("failed to save lookup after append", "error", err)
		}

		result = obj
		return nil
	})

	if err != nil {
		if err.Error() == fmt.Sprintf("lock held by process %s", "") || strings.Contains(err.Error(), "lock held") {
			return nil, ErrLocked
		}
		return nil, err
	}

	return result, nil
}
