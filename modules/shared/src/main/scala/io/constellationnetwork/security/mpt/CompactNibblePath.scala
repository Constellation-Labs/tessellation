package io.constellationnetwork.security.mpt

import scala.collection.immutable.ArraySeq

/** Memory-efficient representation of a nibble path.
  */
final class CompactNibblePath private (
  private val packed: Array[Byte],
  val length: Int
) extends Serializable {

  def isEmpty: Boolean = length == 0
  def nonEmpty: Boolean = length > 0

  /** Get nibble value (0-15) at given index.
    */
  @inline def apply(index: Int): Byte = {
    if (index < 0 || index >= length)
      throw new IndexOutOfBoundsException(s"Index $index out of bounds for length $length")
    val byteIdx = index >> 1
    val byte = packed(byteIdx)
    if ((index & 1) == 0) ((byte >> 4) & 0x0f).toByte
    else (byte & 0x0f).toByte
  }

  /** Alias for apply - get nibble at index. */
  @inline def get(index: Int): Byte = apply(index)

  /** Get nibble at index, returning 0 if out of bounds.
    */
  @inline def getOrEmpty(index: Int): Byte =
    if (index < 0 || index >= length) 0.toByte else apply(index)

  def head: Byte = {
    if (length == 0) throw new NoSuchElementException("head of empty path")
    apply(0)
  }

  def headOption: Option[Byte] =
    if (length == 0) None else Some(apply(0))

  def tail: CompactNibblePath = drop(1)

  def drop(n: Int): CompactNibblePath =
    if (n <= 0) this
    else if (n >= length) CompactNibblePath.empty
    else slice(n, length)

  def take(n: Int): CompactNibblePath =
    if (n <= 0) CompactNibblePath.empty
    else if (n >= length) this
    else slice(0, n)

  def slice(from: Int, until: Int): CompactNibblePath = {
    val start = math.max(0, from)
    val end = math.min(length, until)
    if (start >= end) CompactNibblePath.empty
    else {
      val newLen = end - start
      val result = new Array[Byte](newLen)
      var i = 0
      while (i < newLen) {
        result(i) = apply(start + i)
        i += 1
      }
      CompactNibblePath.fromNibbleValues(result)
    }
  }

  def startsWith(prefix: CompactNibblePath): Boolean =
    if (prefix.length > this.length) false
    else {
      var i = 0
      var matches = true
      while (i < prefix.length && matches) {
        if (this.apply(i) != prefix.apply(i)) matches = false
        i += 1
      }
      matches
    }

  def ++(other: CompactNibblePath): CompactNibblePath =
    if (this.isEmpty) other
    else if (other.isEmpty) this
    else {
      val newLen = this.length + other.length
      val result = new Array[Byte](newLen)
      var i = 0
      while (i < this.length) {
        result(i) = this.apply(i)
        i += 1
      }
      var j = 0
      while (j < other.length) {
        result(i) = other.apply(j)
        i += 1
        j += 1
      }
      CompactNibblePath.fromNibbleValues(result)
    }

  def prepend(nibbleValue: Byte): CompactNibblePath = {
    val newLen = length + 1
    val result = new Array[Byte](newLen)
    result(0) = nibbleValue
    var i = 0
    while (i < length) {
      result(i + 1) = apply(i)
      i += 1
    }
    CompactNibblePath.fromNibbleValues(result)
  }

  def toNibbleValues: Array[Byte] = {
    val result = new Array[Byte](length)
    var i = 0
    while (i < length) {
      result(i) = apply(i)
      i += 1
    }
    result
  }

  def toNibbleSeq: Seq[Nibble] =
    if (length == 0) Seq.empty
    else {
      val arr = new Array[Nibble](length)
      var i = 0
      while (i < length) {
        arr(i) = Nibble.unsafe(apply(i))
        i += 1
      }
      ArraySeq.unsafeWrapArray(arr)
    }

  def toHexString: String =
    if (length == 0) ""
    else {
      val sb = new java.lang.StringBuilder(length)
      var i = 0
      while (i < length) {
        sb.append(CompactNibblePath.hexChars(apply(i).toInt))
        i += 1
      }
      sb.toString
    }

  def commonPrefixLength(other: CompactNibblePath): Int = {
    val minLen = math.min(this.length, other.length)
    var i = 0
    while (i < minLen && this.apply(i) == other.apply(i))
      i += 1
    i
  }

  def commonPrefix(other: CompactNibblePath): CompactNibblePath =
    take(commonPrefixLength(other))

  override def equals(obj: Any): Boolean = obj match {
    case other: CompactNibblePath =>
      if (this.length != other.length) false
      else {
        var i = 0
        var equal = true
        while (i < length && equal) {
          if (this.apply(i) != other.apply(i)) equal = false
          i += 1
        }
        equal
      }
    case _ => false
  }

  override def hashCode(): Int = {
    var h = length
    var i = 0
    while (i < length) {
      h = 31 * h + apply(i).toInt
      i += 1
    }
    h
  }

  override def toString: String = s"CompactNibblePath($toHexString)"

  def equalsSeq(seq: Seq[Nibble]): Boolean =
    if (length != seq.length) false
    else {
      var i = 0
      var equal = true
      val iter = seq.iterator
      while (iter.hasNext && equal) {
        if (apply(i) != iter.next().value) equal = false
        i += 1
      }
      equal
    }

  def compare(other: CompactNibblePath): Int = {
    val minLen = math.min(this.length, other.length)
    var i = 0
    var result = 0
    while (i < minLen && result == 0) {
      result = java.lang.Byte.compare(this.apply(i), other.apply(i))
      i += 1
    }
    if (result != 0) result
    else this.length - other.length
  }
}

object CompactNibblePath {
  private[mpt] val hexChars: Array[Char] = "0123456789abcdef".toCharArray

  val empty: CompactNibblePath = new CompactNibblePath(Array.emptyByteArray, 0)

  def fromNibbleValues(nibbles: Array[Byte]): CompactNibblePath =
    if (nibbles.isEmpty) empty
    else {
      val packedLen = (nibbles.length + 1) >> 1
      val packed = new Array[Byte](packedLen)
      var i = 0
      while (i < nibbles.length - 1) {
        packed(i >> 1) = ((nibbles(i) << 4) | (nibbles(i + 1) & 0x0f)).toByte
        i += 2
      }
      if ((nibbles.length & 1) == 1) {
        packed(packedLen - 1) = (nibbles(nibbles.length - 1) << 4).toByte
      }
      new CompactNibblePath(packed, nibbles.length)
    }

  def fromNibbleSeq(nibbles: Seq[Nibble]): CompactNibblePath =
    if (nibbles.isEmpty) empty
    else {
      val len = nibbles.length
      val values = new Array[Byte](len)
      var i = 0
      val iter = nibbles.iterator
      while (iter.hasNext) {
        values(i) = iter.next().value
        i += 1
      }
      fromNibbleValues(values)
    }

  def fromHexString(hex: String): CompactNibblePath =
    if (hex.isEmpty) empty
    else {
      val values = new Array[Byte](hex.length)
      var i = 0
      while (i < hex.length) {
        val c = hex.charAt(i)
        val nibbleValue: Byte =
          if (c >= '0' && c <= '9') (c - '0').toByte
          else if (c >= 'a' && c <= 'f') (c - 'a' + 10).toByte
          else if (c >= 'A' && c <= 'F') (c - 'A' + 10).toByte
          else throw new IllegalArgumentException(s"Invalid hex char: $c")
        values(i) = nibbleValue
        i += 1
      }
      fromNibbleValues(values)
    }

  def single(value: Byte): CompactNibblePath =
    fromNibbleValues(Array(value))

  def fromNibble(nibble: Nibble): CompactNibblePath =
    fromNibbleValues(Array(nibble.value))

  implicit val ordering: Ordering[CompactNibblePath] = (x: CompactNibblePath, y: CompactNibblePath) => x.compare(y)
}
