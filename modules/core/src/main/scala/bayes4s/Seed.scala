/*
 * Copyright 2026 bayes4s contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bayes4s

/** The immutable state of a splittable generator (SplitMix64).
  *
  * A seed is a value: it is passed in and handed back, never mutated, so a result depends on the
  * seed and never on thread scheduling. [[Seed.next]] is the SplitMix64 step exactly as Steele,
  * Lea and Flood specified it, which `java.util.SplittableRandom` implements independently and
  * which the tests use as the oracle.
  *
  * [[split]] and [[fold]] have no such oracle: the JDK carries a gamma per instance while a seed
  * here is one `Long`, so they are our own derivation, held to determinism, distinctness and
  * independence instead. They mix the state with a second odd constant before advancing it, so an
  * index of 0 does not return the parent stream.
  * **Example.**
  * Advancing a seed by hand, and deriving independent streams for four chains:
  *
  * ```scala
  * val seed = Seed(20261112L)
  *
  * // `next` is a function: the same seed always gives the same pair back.
  * val (advanced, bits) = seed.next
  * val (_, again) = seed.next
  * val reproducible: Boolean = bits == again
  *
  * // A uniform strictly inside (0, 1), so `log(u)` is always finite.
  * val (afterUniform, u) = seed.nextUniform
  *
  * // `fold(i)` derives an independent stream by index. This is how chain `c` is seeded, and why
  * // four chains in parallel give bit-for-bit what four chains in sequence give.
  * val perChain: List[Seed] = (0 until 4).toList.map(c => seed.fold(c.toLong))
  * ```
  */
opaque type Seed = Long

object Seed:

  /** The golden-ratio increment of SplitMix64 (Steele, Lea and Flood 2014). */
  private[bayes4s] val Gamma: Long = 0x9e3779b97f4a7c15L

  /** A second odd constant, for the derivations that are ours rather than SplitMix64's. */
  private val Branch: Long = 0xbf58476d1ce4e5b9L

  def apply(value: Long): Seed = value

  extension (s: Seed)

    /** The raw state. Exposed for tests and for reproducible logging, not for arithmetic. */
    def toLong: Long = s

    /** The next state and the 64 bits it produces. */
    def next: (Seed, Long) =
      val advanced = s + Gamma
      (advanced, mix64(advanced))

    /** Two independent states derived from this one. */
    def split: (Seed, Seed) =
      val advanced = s + Gamma
      (advanced, mix64(advanced ^ Branch))

    /** The state derived by index: chain, draw, horizon. Distinct indices give distinct streams. */
    def fold(i: Long): Seed = mix64(s ^ (Branch * (i + 1L)))

    /** The next state and a uniform strictly inside (0, 1).
      *
      * The top 53 bits are placed at the centre of their bucket rather than at its floor, which
      * is what keeps the result away from both ends without distorting it, and is why no density
      * in this library has to guard against `log(0)`. It lives here, on the seed, so that the
      * `State` interpreter and the batch path cannot disagree about the primitive itself.
      */
    def nextUniform: (Seed, Double) =
      val (advanced, bits) = s.next
      (advanced, ((bits >>> 11) + 0.5) * TwoPowMinus53)

  private val TwoPowMinus53: Double = 1.0 / 9007199254740992.0

  /** The finaliser of SplitMix64: two xor-shift-multiply rounds and a last shift. */
  private def mix64(z0: Long): Long =
    val z1 = (z0 ^ (z0 >>> 30)) * 0xbf58476d1ce4e5b9L
    val z2 = (z1 ^ (z1 >>> 27)) * 0x94d049bb133111ebL
    z2 ^ (z2 >>> 31)
