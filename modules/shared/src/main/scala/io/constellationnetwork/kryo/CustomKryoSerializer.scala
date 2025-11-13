package io.constellationnetwork.kryo

import java.nio.charset.StandardCharsets

import cats.effect.Sync
import cats.syntax.functor._

import io.constellationnetwork.security.hash.Hash

/** Custom Kryo serializer implementation for string serialization.
  *
  * This implementation is adapted from the dag4.js library (tx-encode.ts file), specifically the `kryoSerialize` and `utf8Length` methods.
  *
  * Why this exists:
  *   - Standard Kryo library has compatibility issues with Java 21
  *   - This custom implementation ensures consistent serialization across Java versions
  *   - It replicates the exact byte encoding used by the JavaScript client library
  *
  * The serialization format consists of:
  *   1. A prefix byte (0x03) indicating string type 2. An optional reference flag byte (0x01) if setReferences is true 3. A variable-length
  *      encoded UTF-8 string length 4. The UTF-8 encoded string content
  *
  * @see
  *   https://github.com/StardustCollective/dag4.js for original implementation
  */
object CustomKryoSerializer {

  /** Serializes a string into Kryo-compatible byte format.
    *
    * Adapted from dag4.js `kryoSerialize` method to ensure cross-platform compatibility between Scala/Java and JavaScript implementations.
    *
    * @param msg
    *   The string message to serialize
    * @param setReferences
    *   If true, includes a reference flag byte (0x01) in the output. This matches Kryo's reference tracking behavior.
    * @return
    *   F[Array[Byte]] The serialized bytes wrapped in an effect
    */
  def serialize[F[_]: Sync](msg: String, setReferences: Boolean = false): F[Array[Byte]] =
    Sync[F].delay {
      val msgLength = msg.length + 1

      // Build prefix: type indicator (0x03) + optional reference flag + encoded length
      val prefix = Array(0x03.toByte) ++
        (if (setReferences) Array(0x01.toByte) else Array.empty[Byte]) ++
        utf8Length(msgLength)

      // Encode the actual message content as UTF-8 bytes
      val coded = msg.getBytes(StandardCharsets.UTF_8)

      prefix ++ coded
    }

  /** Serializes a string and computes its hash.
    *
    * This is useful for creating transaction hashes and other cryptographic operations that require deterministic serialization.
    *
    * @param msg
    *   The string message to serialize and hash
    * @param setReferences
    *   If true, includes reference tracking in serialization
    * @return
    *   F[Hash] The computed hash of the serialized bytes
    */
  def hash[F[_]: Sync](msg: String, setReferences: Boolean = false): F[Hash] =
    for {
      bytes <- serialize(msg, setReferences)
    } yield Hash.fromBytes(bytes)

  /** Encodes a string length using Kryo's variable-length encoding scheme.
    *
    * This is a direct port of the `utf8Length` method from dag4.js (tx-encode.ts).
    *
    * The encoding uses variable-length format where:
    *   - Bit 8 (0x80) is always set to indicate UTF-8 encoding
    *   - Bit 7 (0x40) is set when multiple bytes are needed
    *   - The length is encoded in 6-bit chunks across multiple bytes if needed
    *
    * Length ranges:
    *   - 1 byte: 0 to 63 (2^6 - 1)
    *   - 2 bytes: 64 to 8,191 (2^13 - 1)
    *   - 3 bytes: 8,192 to 1,048,575 (2^20 - 1)
    *   - 4 bytes: 1,048,576 to 134,217,727 (2^27 - 1)
    *   - 5 bytes: 134,217,728+ (2^27 and above)
    *
    * @param value
    *   The length value to encode
    * @return
    *   Array[Byte] The variable-length encoded representation
    */
  private def utf8Length(value: Int): Array[Byte] =
    if (value >>> 6 == 0) {
      // Length fits in 1 byte (< 64)
      // Set bit 8 to indicate UTF-8
      Array((value | 0x80).toByte)
    } else if (value >>> 13 == 0) {
      // Length fits in 2 bytes (64-8191)
      // First byte: set bits 7 and 8, contains lower 6 bits
      // Second byte: contains upper bits
      Array(
        (value | 0x40 | 0x80).toByte, // Set bit 7 and 8
        (value >>> 6).toByte
      )
    } else if (value >>> 20 == 0) {
      // Length fits in 3 bytes (8192-1048575)
      Array(
        (value | 0x40 | 0x80).toByte, // Set bit 7 and 8
        ((value >>> 6) | 0x80).toByte, // Set bit 8, continuation byte
        (value >>> 13).toByte
      )
    } else if (value >>> 27 == 0) {
      // Length fits in 4 bytes (1048576-134217727)
      Array(
        (value | 0x40 | 0x80).toByte, // Set bit 7 and 8
        ((value >>> 6) | 0x80).toByte, // Set bit 8, continuation byte
        ((value >>> 13) | 0x80).toByte, // Set bit 8, continuation byte
        (value >>> 20).toByte
      )
    } else {
      // Length fits in 5 bytes (134217728+)
      Array(
        (value | 0x40 | 0x80).toByte, // Set bit 7 and 8
        ((value >>> 6) | 0x80).toByte, // Set bit 8, continuation byte
        ((value >>> 13) | 0x80).toByte, // Set bit 8, continuation byte
        ((value >>> 20) | 0x80).toByte, // Set bit 8, continuation byte
        (value >>> 27).toByte
      )
    }
}
