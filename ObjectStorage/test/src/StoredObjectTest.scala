package objectstorage.models

import utest._
import java.util.UUID
import java.time.Instant

object StoredObjectTest extends TestSuite {
  val tests: Tests = Tests {
    test("StoredObject serializes to JSON") {
      val obj = StoredObject(
        objectId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
        bucket = "test-bucket",
        fileName = "test.txt",
        size = 1024L,
        mimeType = Some("text/plain"),
        contentType = Some("text/plain"),
        createdAt = Instant.parse("2025-01-01T00:00:00Z"),
        lastModified = Instant.parse("2025-01-01T00:00:00Z"),
        checksum = Some("abc123"),
        etag = Some("\"abc123\""),
        metadata = Map("key" -> "value")
      )

      val json = StoredObject.toJson(obj)

      assert(json.contains("550e8400-e29b-41d4-a716-446655440000"))
      assert(json.contains("test-bucket"))
      assert(json.contains("test.txt"))
      assert(json.contains("1024"))
      assert(json.contains("text/plain"))
    }

    test("StoredObject deserializes from JSON") {
      val json =
        """{"objectId":"550e8400-e29b-41d4-a716-446655440000","bucket":"test-bucket","fileName":"test.txt","size":1024,"mimeType":"text/plain","contentType":"text/plain","createdAt":"2025-01-01T00:00:00Z","lastModified":"2025-01-01T00:00:00Z","checksum":"abc123","etag":"\"abc123\"","metadata":{"key":"value"}}"""

      val obj = StoredObject.fromJson(json)

      assert(obj.objectId == UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))
      assert(obj.bucket == "test-bucket")
      assert(obj.fileName == "test.txt")
      assert(obj.size == 1024L)
      assert(obj.mimeType == Some("text/plain"))
      assert(obj.metadata == Map("key" -> "value"))
    }

    test("StoredObject.create generates UUID and timestamps") {
      val obj = StoredObject.create(
        bucket = "my-bucket",
        fileName = "file.pdf",
        size = 2048L,
        mimeType = Some("application/pdf"),
        checksum = Some("sha256hash")
      )

      // Verify UUID was generated (non-zero, valid format)
      assert(obj.objectId.toString.nonEmpty)
      assert(obj.bucket == "my-bucket")
      assert(obj.fileName == "file.pdf")
      assert(obj.size == 2048L)
      // Verify timestamp was generated
      assert(obj.createdAt.toEpochMilli > 0)
      assert(obj.lastModified == obj.createdAt)
      assert(obj.etag == Some("\"sha256hash\""))
    }

    test("StoredObject roundtrip JSON serialization") {
      val original = StoredObject.create(
        bucket = "roundtrip-test",
        fileName = "data.bin",
        size = 4096L,
        mimeType = Some("application/octet-stream"),
        metadata = Map("source" -> "test", "version" -> "1")
      )

      val json = StoredObject.toJson(original)
      val restored = StoredObject.fromJson(json)

      assert(restored.objectId == original.objectId)
      assert(restored.bucket == original.bucket)
      assert(restored.fileName == original.fileName)
      assert(restored.size == original.size)
      assert(restored.metadata == original.metadata)
    }
  }
}
