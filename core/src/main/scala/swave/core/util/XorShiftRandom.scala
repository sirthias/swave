/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.util

import scala.annotation.tailrec
import scala.collection.immutable.Stream
import swave.core.macros._

/**
 * xorshift128+ random number generator as proposed by Sebastiano Vigna in
 * "Further scramblings of Marsaglia’s xorshift generators" (April 2014):
 * http://vigna.di.unimi.it/ftp/papers/xorshiftplus.pdf
 *
 * In this paper Vigna claims that xorshift128+ "is currently the fastest full-period generator we are aware of that
 * does not fail systematically any BigCrush test (not even reversed), making it an excellent drop-in substitute for
 * the low-dimensional generators found in many programming languages."
 *
 * Further:
 * "For example, the current default pseudorandom number generator of the Erlang language is a custom xorshift116+
 * generator designed by the author using 58-bit integers and shifts (Erlang uses the upper 6 bits for object metadata,
 * so using 64-bit integers would make the algorithm significantly slower); and the JavaScript engines of Chrome,
 * Firefox and Safari are based on xorshift128+.
 * xorshift128+ can also be easily implemented in hardware, as it requires just three shift, four xors and an addition."
 *
 * @param seed0 the lower half of the 128-bit random seed, must be non-zero when seed1 is zero
 * @param seed1 the upper half of the 128-bit random seed, must be non-zero when seed0 is zero
 */
final class XorShiftRandom(seed0: Long, seed1: Long) {
  requireArg(seed0 != 0 || seed1 != 0)

  private var s0 = seed0
  private var s1 = seed1

  private val DOUBLE_UNIT = 1.1102230246251565E-16 // 0x1.0p-53 which is 1.0 / (1L << 53)
  private val FLOAT_UNIT = 5.9604645E-8f // 0x1.0p-24 which is 1.0 / (1L << 24)

  /**
   * Returns the current 128-bit seed.
   */
  def seed: (Long, Long) = s0 → s1

  /**
   * Returns the seed as a 32-digit hex string.
   */
  def seedAsString: String = XorShiftRandom.formatSeed(s0, s1)

  /**
   * Returns a pseudo-random 64-bit Long value.
   */
  def nextLong(): Long = {
    var x = s0
    val y = s1
    s0 = y
    x ^= x << 23
    s1 = x ^ y ^ (x >> 17) ^ (y >> 26)
    s1 + y
  }

  /**
   * Returns a pseudo-random Long value that is >= 0 and < `bound`.
   * The `bound` must be > 0.
   */
  def nextLong(bound: Long): Long = {
    requireArg(bound > 0)
    val mask = bound - 1
    if ((bound & mask) == 0) nextLong() & mask // bound is a power of 2
    else (nextLong() >>> 1) % bound // bound is not a power of 2
  }

  /**
   * Returns a pseudo-random 32-bit Int value.
   */
  def nextInt(): Int = nextLong().toInt

  /**
   * Returns a pseudo-random Int value that is >= 0 and < `bound`.
   * The `bound` must be > 0.
   */
  def nextInt(bound: Int): Int = {
    requireArg(bound > 0)
    val mask = bound - 1
    if ((bound & mask) == 0) nextInt() & mask // bound is a power of 2
    else (nextInt() >>> 1) % bound // bound is not a power of 2
  }

  /**
   * Returns a pseudo-random double >= 0 and < 1.
   */
  def nextDouble(): Double = (nextLong() & ((1L << 53) - 1)) * DOUBLE_UNIT

  /**
   * Returns a pseudo-random float >= 0 and < 1.
   */
  def nextFloat(): Float = (nextLong() & ((1L << 24) - 1)) * FLOAT_UNIT

  /**
   * Returns a pseudo-random boolean.
   */
  def nextBoolean(): Boolean = (nextLong() & 1) != 0

  /**
   * Fills the given array with pseudo-random bytes.
   */
  def nextBytes(bytes: Array[Byte]): Unit = {
    @tailrec def rec(ix: Int): Unit =
      if (ix >= 0) {
        val l = nextLong()
        bytes(ix) = l.toByte
        // one case where having a switch with case fall-through would be nice!
        if (ix >= 1) bytes(ix - 1) = (l >> 8).toByte
        if (ix >= 2) bytes(ix - 2) = (l >> 16).toByte
        if (ix >= 3) bytes(ix - 3) = (l >> 24).toByte
        if (ix >= 4) bytes(ix - 4) = (l >> 32).toByte
        if (ix >= 5) bytes(ix - 5) = (l >> 40).toByte
        if (ix >= 6) bytes(ix - 6) = (l >> 48).toByte
        if (ix >= 7) bytes(ix - 7) = (l >> 56).toByte
        rec(ix - 8)
      }
    rec(bytes.length - 1)
  }

  /**
   * Returns true with a probability of 1 / 2^^pow, i.e.
   *   100% for pow = 0
   *    50% for pow = 1
   *    25% for pow = 2
   *  12.5% for pow = 3, etc.
   *
   * Only the 5 lowest bits of `pow` are actually used, so results might be
   * somewhat unexpected for negative values.
   */
  def decidePow2(pow: Int = 1): Boolean = (nextLong() & ((1 << pow) - 1)) == 0

  /**
   * Returns true with a probability of `probTrue`,
   * i.e. `decide(0.75)` will return true in about 3 of 4 calls on average.
   */
  def decide(probTrue: Double): Boolean = nextDouble() < probTrue

  /**
   * Picks one of the given sequence's element with about equal probability.
   * `seq` must be non-empty.
   */
  def pick[T](seq: Seq[T]): T = {
    requireArg(seq.nonEmpty)
    seq(nextInt(seq.size))
  }

  /**
   * Shuffles the elements of the given array in place in such a way that each
   * possible permutations is produced with about equal probability.
   * The algorithm (modern impl of the Fisher–Yates shuffle) has linear runtime.
   */
  def shuffle_![T <: AnyRef](array: Array[T]): Unit = {
    @tailrec def rec(i: Int): Unit =
      if (i > 0) {
        val j = nextInt(i + 1)
        val x = array(i)
        array(i) = array(j)
        array(j) = x
        rec(i - 1)
      }
    rec(array.length - 1)
  }

  def asScalaRandom = new XorShiftRandom.Random(this)
}

object XorShiftRandom {

  /**
   * Creates a new XorShiftRandom instance seeded with the current `System.nanoTime()`.
   */
  def apply(): XorShiftRandom = XorShiftRandom(System.nanoTime(), System.nanoTime())

  /**
   * Creates a new XorShiftRandom instance seeded with the given Longs.
   */
  def apply(seed0: Long, seed1: Long): XorShiftRandom = new XorShiftRandom(seed0, seed1)

  /**
   * Creates a new XorShiftRandom instance seeded with the given Long tuple.
   */
  def apply(seed: (Long, Long)): XorShiftRandom = new XorShiftRandom(seed._1, seed._2)

  /**
   * Creates a new XorShiftRandom instance seeded either with a Long tuple or the current `System.nanoTime()`.
   */
  def apply(seed: Option[(Long, Long)]): XorShiftRandom =
    seed match {
      case Some(tuple) ⇒ XorShiftRandom(tuple)
      case None        ⇒ XorShiftRandom()
    }

  /**
   * Creates a new XorShiftRandom instance seeded with the given hex string.
   * The given string must either be emtpy or consist of exactly 32 hex characters.
   * If the string is empty the instance is seeded with current `System.nanoTime()`.
   */
  def apply(seed: String): XorShiftRandom = XorShiftRandom(parseSeed(seed))

  /**
   * Formats a pair of Longs into a 32-digit hex-string that can be used with the `parseSeed` method.
   */
  def formatSeed(seed: (Long, Long)): String = formatSeed(seed._1, seed._2)

  /**
   * Formats a pair of Longs into a 32-digit hex-string that can be used with the `parseSeed` method.
   */
  def formatSeed(seed0: Long, seed1: Long): String = {
    def hex(l: Long) = l.toHexString.reverse.padTo(16, '0').reverse
    hex(seed0) + hex(seed1)
  }

  /**
   * Parses a 32-digit hex-string into a pair of Longs, if the given string is non-empty.
   */
  def parseSeed(seed: String): Option[(Long, Long)] =
    seed.toOption map { s ⇒
      import java.lang.Long.parseLong
      requireArg(s.length == 32)
      try parseLong(s.substring(0, 16), 16) → parseLong(s.substring(16, 32), 16)
      catch {
        case e: NumberFormatException ⇒ throw new IllegalArgumentException("Invalid random seed string", e)
      }
    }

  // unfortunately scala.util.Random isn't designed for reusability
  // so we have to override and reimplement almost all its methods here
  class Random(val xorShiftRandom: XorShiftRandom) extends scala.util.Random(null) {
    private[this] var haveNextNextGaussian = false
    private[this] var nextNextGaussian: Double = _

    override def nextBoolean(): Boolean = xorShiftRandom.nextBoolean()
    override def nextBytes(bytes: Array[Byte]): Unit = xorShiftRandom.nextBytes(bytes)
    override def nextDouble(): Double = xorShiftRandom.nextDouble()
    override def nextFloat(): Float = xorShiftRandom.nextFloat()
    override def nextGaussian(): Double =
      // See Knuth, ACP, Section 3.4.1 Algorithm C.
      if (haveNextNextGaussian) {
        haveNextNextGaussian = false
        nextNextGaussian
      } else {
        @tailrec def rec(): Double = {
          val v1 = 2 * nextDouble() - 1 // between -1 and 1
          val v2 = 2 * nextDouble() - 1 // between -1 and 1
          val s = v1 * v1 + v2 * v2
          if (0 != s && s < 1) {
            val multiplier = StrictMath.sqrt(-2 * StrictMath.log(s) / s)
            nextNextGaussian = v2 * multiplier
            haveNextNextGaussian = true
            v1 * multiplier
          } else rec()
        }
        rec()
      }
    override def nextInt(): Int = xorShiftRandom.nextInt()
    override def nextInt(n: Int): Int = xorShiftRandom.nextInt(n)
    override def nextLong(): Long = xorShiftRandom.nextLong()
    override def nextString(length: Int): String = {
      val chars = Array.fill(length) {
        val surrogateStart = 0xD800
        val res = nextInt(surrogateStart - 1) + 1
        res.toChar
      }
      new String(chars)
    }
    override def nextPrintableChar(): Char = (nextInt(127 - 33) + 33).toChar
    override def setSeed(seed: Long): Unit = {
      requireArg(seed != 0)
      xorShiftRandom.s0 = seed
      xorShiftRandom.s1 = seed
    }
    override def alphanumeric: Stream[Char] = Stream continually {
      val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
      chars charAt (xorShiftRandom nextInt chars.length)
    }
  }
}
