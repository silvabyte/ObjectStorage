package storage

import (
	"errors"
	"time"
)

// Tenant identifies an isolated storage namespace.
type Tenant struct {
	TenantID string
	UserID   string
}

// StoredObject represents a stored file with metadata.
type StoredObject struct {
	ObjectID     string            `json:"objectId"`
	Bucket       string            `json:"bucket"`
	FileName     string            `json:"fileName"`
	Size         int64             `json:"size"`
	MimeType     string            `json:"mimeType,omitempty"`
	ContentType  string            `json:"contentType,omitempty"`
	CreatedAt    time.Time         `json:"createdAt"`
	LastModified time.Time         `json:"lastModified"`
	Checksum     string            `json:"checksum,omitempty"`
	ETag         string            `json:"etag,omitempty"`
	Metadata     map[string]string `json:"metadata"`
}

var (
	ErrNotFound = errors.New("not found")
	ErrLocked   = errors.New("file is locked")
)
