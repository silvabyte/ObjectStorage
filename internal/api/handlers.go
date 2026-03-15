package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"

	"github.com/danielgtaylor/huma/v2"
	"github.com/silvabyte/objectstorage/internal/storage"
)

// FileResponseBody is the standard response for file operations.
type FileResponseBody struct {
	File   storage.StoredObject `json:"file"`
	Status string               `json:"status" example:"success"`
}

// ListFilesResponseBody is the response for listing files.
type ListFilesResponseBody struct {
	Files  []storage.StoredObject `json:"files"`
	Status string                 `json:"status" example:"success"`
}

func mapStoreError(err error, objectID string) error {
	switch {
	case errors.Is(err, storage.ErrNotFound):
		return huma.Error404NotFound(fmt.Sprintf("File %s not found", objectID))
	case errors.Is(err, storage.ErrLocked):
		return huma.Error409Conflict("File is currently locked")
	default:
		return huma.Error500InternalServerError(err.Error())
	}
}

func writeJSON(w io.Writer, v any) {
	_ = json.NewEncoder(w).Encode(v)
}

// --- List Files ---

type ListFilesOutput struct {
	Body ListFilesResponseBody
}

func handleListFiles(store *storage.FileStore) func(ctx context.Context, input *struct{}) (*ListFilesOutput, error) {
	return func(ctx context.Context, input *struct{}) (*ListFilesOutput, error) {
		tenant := mustTenant(ctx)
		files, err := store.List(tenant)
		if err != nil {
			return nil, huma.Error500InternalServerError(err.Error())
		}
		return &ListFilesOutput{Body: ListFilesResponseBody{Files: files, Status: "success"}}, nil
	}
}

// --- Get File Metadata ---

type GetFileInput struct {
	ObjectID string `path:"objectId"`
}

type FileOutput struct {
	Body FileResponseBody
}

func handleGetFile(store *storage.FileStore) func(ctx context.Context, input *GetFileInput) (*FileOutput, error) {
	return func(ctx context.Context, input *GetFileInput) (*FileOutput, error) {
		if !storage.IsValidUUID(input.ObjectID) {
			return nil, huma.Error400BadRequest("Invalid UUID format")
		}
		tenant := mustTenant(ctx)
		obj, err := store.Get(tenant, input.ObjectID)
		if err != nil {
			return nil, mapStoreError(err, input.ObjectID)
		}
		return &FileOutput{Body: FileResponseBody{File: *obj, Status: "success"}}, nil
	}
}

// --- Download File ---

type DownloadFileInput struct {
	ObjectID string `path:"objectId"`
}

func handleDownloadFile(store *storage.FileStore) func(ctx context.Context, input *DownloadFileInput) (*huma.StreamResponse, error) {
	return func(ctx context.Context, input *DownloadFileInput) (*huma.StreamResponse, error) {
		if !storage.IsValidUUID(input.ObjectID) {
			return nil, huma.Error400BadRequest("Invalid UUID format")
		}
		tenant := mustTenant(ctx)
		f, obj, err := store.Download(tenant, input.ObjectID)
		if err != nil {
			return nil, mapStoreError(err, input.ObjectID)
		}

		contentType := obj.ContentType
		if contentType == "" {
			contentType = "application/octet-stream"
		}

		return &huma.StreamResponse{
			Body: func(ctx huma.Context) {
				ctx.SetHeader("Content-Type", contentType)
				ctx.SetStatus(http.StatusOK)
				_, _ = io.Copy(ctx.BodyWriter(), f)
				f.Close()
			},
		}, nil
	}
}

// --- Checksum Lookup ---

type ChecksumLookupInput struct {
	Checksum string `path:"checksum"`
}

func handleChecksumLookup(store *storage.FileStore) func(ctx context.Context, input *ChecksumLookupInput) (*FileOutput, error) {
	return func(ctx context.Context, input *ChecksumLookupInput) (*FileOutput, error) {
		tenant := mustTenant(ctx)
		obj, err := store.LookupByChecksum(tenant, input.Checksum)
		if err != nil {
			return nil, mapStoreError(err, input.Checksum)
		}
		return &FileOutput{Body: FileResponseBody{File: *obj, Status: "success"}}, nil
	}
}

// --- Upload Stream ---

type UploadInput struct {
	FileName string `header:"x-file-name"`
	MimeType string `header:"x-mimetype" required:"false"`
	RawBody  []byte
}

type UploadOutput struct {
	Status int
	Body   FileResponseBody
}

func handleUpload(store *storage.FileStore) func(ctx context.Context, input *UploadInput) (*UploadOutput, error) {
	return func(ctx context.Context, input *UploadInput) (*UploadOutput, error) {
		if input.FileName == "" {
			return nil, huma.Error400BadRequest("Missing x-file-name header")
		}
		tenant := mustTenant(ctx)

		obj, dup, err := store.Upload(tenant, bytes.NewReader(input.RawBody), input.FileName, input.MimeType)
		if err != nil {
			return nil, huma.Error500InternalServerError(err.Error())
		}

		status := http.StatusCreated
		if dup {
			status = http.StatusOK
		}

		return &UploadOutput{
			Status: status,
			Body:   FileResponseBody{File: *obj, Status: "success"},
		}, nil
	}
}

// --- Upload Form (raw HTTP handler — multipart doesn't fit huma's model) ---

func handleUploadFormRaw(store *storage.FileStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tenant := mustTenant(r.Context())

		if err := r.ParseMultipartForm(32 << 20); err != nil {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadRequest)
			writeJSON(w, map[string]string{"message": "Invalid multipart form", "status": "error"})
			return
		}

		file, header, err := r.FormFile("file")
		if err != nil {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadRequest)
			writeJSON(w, map[string]string{"message": "Missing file field", "status": "error"})
			return
		}
		defer file.Close()

		obj, err := store.UploadForm(tenant, file, header.Filename)
		if err != nil {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusInternalServerError)
			writeJSON(w, map[string]string{"message": err.Error(), "status": "error"})
			return
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		writeJSON(w, FileResponseBody{File: *obj, Status: "success"})
	}
}

// --- Delete File ---

type DeleteFileInput struct {
	ObjectID string `path:"objectId"`
}

func handleDeleteFile(store *storage.FileStore) func(ctx context.Context, input *DeleteFileInput) (*FileOutput, error) {
	return func(ctx context.Context, input *DeleteFileInput) (*FileOutput, error) {
		if !storage.IsValidUUID(input.ObjectID) {
			return nil, huma.Error400BadRequest("Invalid UUID format")
		}
		tenant := mustTenant(ctx)
		obj, err := store.Delete(tenant, input.ObjectID)
		if err != nil {
			return nil, mapStoreError(err, input.ObjectID)
		}
		return &FileOutput{Body: FileResponseBody{File: *obj, Status: "success"}}, nil
	}
}

// --- NDJSON Append ---

type NDJSONAppendInput struct {
	ObjectID string `path:"objectId"`
	RawBody  []byte
}

func handleNDJSONAppend(store *storage.FileStore) func(ctx context.Context, input *NDJSONAppendInput) (*FileOutput, error) {
	return func(ctx context.Context, input *NDJSONAppendInput) (*FileOutput, error) {
		if !storage.IsValidUUID(input.ObjectID) {
			return nil, huma.Error400BadRequest("Invalid UUID format")
		}
		tenant := mustTenant(ctx)
		obj, err := store.Append(tenant, input.ObjectID, input.RawBody)
		if err != nil {
			return nil, mapStoreError(err, input.ObjectID)
		}
		return &FileOutput{Body: FileResponseBody{File: *obj, Status: "success"}}, nil
	}
}

// --- NDJSON Stream ---

type NDJSONStreamInput struct {
	ObjectID string `path:"objectId"`
}

func handleNDJSONStream(store *storage.FileStore) func(ctx context.Context, input *NDJSONStreamInput) (*huma.StreamResponse, error) {
	return func(ctx context.Context, input *NDJSONStreamInput) (*huma.StreamResponse, error) {
		if !storage.IsValidUUID(input.ObjectID) {
			return nil, huma.Error400BadRequest("Invalid UUID format")
		}
		tenant := mustTenant(ctx)
		f, _, err := store.Download(tenant, input.ObjectID)
		if err != nil {
			return nil, mapStoreError(err, input.ObjectID)
		}

		return &huma.StreamResponse{
			Body: func(ctx huma.Context) {
				ctx.SetHeader("Content-Type", "application/x-ndjson")
				ctx.SetStatus(http.StatusOK)
				_, _ = io.Copy(ctx.BodyWriter(), f)
				f.Close()
			},
		}, nil
	}
}
