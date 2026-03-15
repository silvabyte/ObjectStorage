package api

import (
	"context"

	"github.com/danielgtaylor/huma/v2"
	"github.com/go-chi/chi/v5"
	"github.com/silvabyte/objectstorage/internal/storage"
)

// RegisterRoutes registers all API routes on the huma API and chi router.
func RegisterRoutes(api huma.API, router chi.Router, store *storage.FileStore) {
	// Health endpoints
	type StatusOutput struct {
		Body struct {
			Status string `json:"status" example:"ok"`
		}
	}
	type PingOutput struct {
		Body struct {
			Message string `json:"message" example:"pong"`
		}
	}

	huma.Register(api, huma.Operation{
		OperationID: "root",
		Method:      "GET",
		Path:        "/",
		Summary:     "Root",
		Description: "Root endpoint",
		Tags:        []string{"Health"},
	}, func(ctx context.Context, input *struct{}) (*StatusOutput, error) {
		out := &StatusOutput{}
		out.Body.Status = "ok"
		return out, nil
	})

	huma.Register(api, huma.Operation{
		OperationID: "ping",
		Method:      "GET",
		Path:        "/api/v1/ping",
		Summary:     "Ping",
		Description: "Ping endpoint",
		Tags:        []string{"Health"},
	}, func(ctx context.Context, input *struct{}) (*PingOutput, error) {
		out := &PingOutput{}
		out.Body.Message = "pong"
		return out, nil
	})

	huma.Register(api, huma.Operation{
		OperationID: "health",
		Method:      "GET",
		Path:        "/api/v1/health",
		Summary:     "Health Check",
		Description: "Health check endpoint",
		Tags:        []string{"Health"},
	}, func(ctx context.Context, input *struct{}) (*StatusOutput, error) {
		out := &StatusOutput{}
		out.Body.Status = "ok"
		return out, nil
	})

	// File endpoints
	huma.Register(api, huma.Operation{
		OperationID: "listFiles",
		Method:      "GET",
		Path:        "/api/v1/files/list",
		Summary:     "List Files",
		Description: "List all files for the authenticated tenant",
		Tags:        []string{"Files"},
	}, handleListFiles(store))

	huma.Register(api, huma.Operation{
		OperationID: "getFileMetadata",
		Method:      "GET",
		Path:        "/api/v1/files/metadata/{objectId}",
		Summary:     "Get File Metadata",
		Description: "Get metadata for a specific file",
		Tags:        []string{"Metadata"},
	}, handleGetFile(store))

	huma.Register(api, huma.Operation{
		OperationID: "downloadFile",
		Method:      "GET",
		Path:        "/api/v1/files/{objectId}",
		Summary:     "Download File",
		Description: "Download a file by its object ID",
		Tags:        []string{"Files"},
	}, handleDownloadFile(store))

	huma.Register(api, huma.Operation{
		OperationID: "checksumLookup",
		Method:      "GET",
		Path:        "/api/v1/files/checksum/{checksum}",
		Summary:     "Lookup by Checksum",
		Description: "Find a file by its SHA-256 checksum",
		Tags:        []string{"Files"},
	}, handleChecksumLookup(store))

	huma.Register(api, huma.Operation{
		OperationID: "uploadFile",
		Method:      "POST",
		Path:        "/api/v1/files",
		Summary:     "Upload File (Stream)",
		Description: "Upload a file via streaming body. Returns 201 for new files, 200 for duplicates.",
		Tags:        []string{"Files"},
	}, handleUpload(store))

	// Form upload registered directly on chi (multipart doesn't fit huma's input model)
	router.Post("/api/v1/files/form", handleUploadFormRaw(store))

	huma.Register(api, huma.Operation{
		OperationID: "deleteFile",
		Method:      "DELETE",
		Path:        "/api/v1/files/{objectId}",
		Summary:     "Delete File",
		Description: "Delete a file by its object ID",
		Tags:        []string{"Files"},
	}, handleDeleteFile(store))

	// NDJSON endpoints
	huma.Register(api, huma.Operation{
		OperationID: "ndjsonAppend",
		Method:      "POST",
		Path:        "/api/v1/files/{objectId}/ndjson/append/stream",
		Summary:     "Append NDJSON",
		Description: "Append data to an existing NDJSON file",
		Tags:        []string{"NDJSON"},
	}, handleNDJSONAppend(store))

	huma.Register(api, huma.Operation{
		OperationID: "ndjsonStream",
		Method:      "GET",
		Path:        "/api/v1/files/{objectId}/ndjson/items/stream",
		Summary:     "Stream NDJSON",
		Description: "Stream the contents of an NDJSON file",
		Tags:        []string{"NDJSON"},
	}, handleNDJSONStream(store))
}
