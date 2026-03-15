package storage

import (
	"encoding/json"
	"testing"
	"time"
)

func TestStoredObjectJSONRoundtrip(t *testing.T) {
	now := time.Now().Truncate(time.Second)
	obj := StoredObject{
		ObjectID:     "abc-123",
		Bucket:       "test-bucket",
		FileName:     "test.txt",
		Size:         1024,
		MimeType:     "text/plain",
		ContentType:  "text/plain",
		CreatedAt:    now,
		LastModified: now,
		Checksum:     "sha256-abc",
		ETag:         "etag-123",
		Metadata:     map[string]string{"key": "value"},
	}

	data, err := json.Marshal(obj)
	if err != nil {
		t.Fatalf("marshal error: %v", err)
	}

	var decoded StoredObject
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("unmarshal error: %v", err)
	}

	if decoded.ObjectID != obj.ObjectID {
		t.Errorf("ObjectID mismatch: got %s, want %s", decoded.ObjectID, obj.ObjectID)
	}
	if decoded.FileName != obj.FileName {
		t.Errorf("FileName mismatch: got %s, want %s", decoded.FileName, obj.FileName)
	}
	if decoded.Size != obj.Size {
		t.Errorf("Size mismatch: got %d, want %d", decoded.Size, obj.Size)
	}
	if decoded.Metadata["key"] != "value" {
		t.Errorf("Metadata mismatch: got %v", decoded.Metadata)
	}
}

func TestStoredObjectJSONOmitEmpty(t *testing.T) {
	obj := StoredObject{
		ObjectID:     "abc-123",
		Bucket:       "test-bucket",
		FileName:     "test.txt",
		Size:         100,
		CreatedAt:    time.Now(),
		LastModified: time.Now(),
		Metadata:     map[string]string{},
	}

	data, err := json.Marshal(obj)
	if err != nil {
		t.Fatalf("marshal error: %v", err)
	}

	var raw map[string]interface{}
	if err := json.Unmarshal(data, &raw); err != nil {
		t.Fatalf("unmarshal error: %v", err)
	}

	for _, field := range []string{"mimeType", "contentType", "checksum", "etag"} {
		if _, exists := raw[field]; exists {
			t.Errorf("expected field %q to be omitted, but it was present", field)
		}
	}
}
