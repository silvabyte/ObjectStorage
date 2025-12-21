# Auth Simplification Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Simplify authentication to use a single API Key from environment variables and exclude public routes (OpenAPI, health) from authentication.

**Architecture:**
- Global `withAuth` decorator in `mainDecorators` (Secure by Default).
- Whitelist public paths in the decorator.
- Verify `x-api-key` header against `API_KEY` env var.
- Extract `x-tenant-id` / `x-user-id` from headers after API Key validation.

**Tech Stack:** Scala 3, Cask, uPickle

### Task 1: Update Configuration

**Files:**
- Modify: `ObjectStorage/src/objectstorage/config/Config.scala`

**Steps:**
1. Remove `AuthConfig` case class.
2. Remove `JWT_SECRET`, `JWT_ISSUER`, `JWT_AUDIENCE`, `AUTH_PROVIDER`.
3. Add `API_KEY`: String (Required).
4. Remove `authConfig` lazy val.

### Task 2: Simplify Decorators & Logic

**Files:**
- Modify: `ObjectStorage/src/objectstorage/decorators/Decorators.scala`
- Delete: `ObjectStorage/src/objectstorage/auth/` (Recursive)

**Steps:**
1. Delete the `auth` package and all its contents (`AuthProvider`, `JwtAuthProvider`, `NoAuthProvider`, `AuthResult`).
2. In `Decorators.scala`:
   - Remove `auth` imports.
   - Define `private val publicPaths = Set("/", "/api/v1/ping", "/api/v1/health", "/openapi")`.
   - Implement `withAuth`:
     - Check request path. If in `publicPaths` (or starts with `/openapi`), call delegate immediately.
     - Check `x-api-key` header == `Config.API_KEY`.
     - If invalid: 401.
     - If valid: Extract `x-tenant-id`, `x-user-id`.
     - If headers missing: 400/401.
     - If all good: Store `AuthenticatedUser` in attachment.

### Task 3: Cleanup Dependencies

**Files:**
- Modify: `build.mill`

**Steps:**
1. Remove `ivy"com.auth0:java-jwt:4.4.0"`.

### Task 4: Verify

**Steps:**
1. Run `mill ObjectStorage.test` to ensure nothing broke.
2. Manual verification via curl (optional/if possible) or unit test update.
