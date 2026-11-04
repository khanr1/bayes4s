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

import bayes4s.testkit.Approx
import weaver.SimpleIOSuite

/** SplitMix64 against `java.util.SplittableRandom`.
  *
  * The JDK class is an independent implementation of the same algorithm, written by its authors
  * (Steele, Lea and Flood 2014): comparing against it tests our implementation without sharing a
  * line of code with it. `split` and `fold` have no such oracle — the JDK carries a per-instance
  * gamma while a `Seed` is one `Long` — so they are held to determinism, distinctness and
  * independence instead, and that limit is recorded in docs/validation.md.
  */
object SeedSuite extends SimpleIOSuite with Approx:

  private val seeds = List(0L, 1L, 42L, -1L, 20261104L, Long.MaxValue, Long.MinValue)

  private def stream(seed: Seed, n: Int): List[Long] =
    List.unfold((seed, 0)) { (s, i) =>
      if i == n then None
      else
        val (nextSeed, value) = s.next
        Some((value, (nextSeed, i + 1)))
    }

  pureTest("1000 draws match java.util.SplittableRandom, bit for bit") {
    forEach(seeds) { raw =>
      val jdk = new java.util.SplittableRandom(raw)
      val expected = List.fill(1000)(jdk.nextLong())
      val obtained = stream(Seed(raw), 1000)
      val firstDifference = obtained.zip(expected).indexWhere((a, b) => a != b)
      check(
        firstDifference < 0,
        s"seed $raw: draw $firstDifference differs, " +
          s"ours ${obtained.lift(firstDifference)} against ${expected.lift(firstDifference)}"
      )
    }
  }

  pureTest("the same seed gives the same stream, and a different seed a different one") {
    check(stream(Seed(7L), 50) == stream(Seed(7L), 50), "the same seed drifted")
      .and(check(stream(Seed(7L), 50) != stream(Seed(8L), 50), "two seeds gave the same stream"))
  }

  pureTest("next is a function: calling it twice on the same value gives the same pair") {
    forEach(seeds) { raw =>
      val s = Seed(raw)
      val (a, x) = s.next
      val (b, y) = s.next
      check(a.toLong == b.toLong && x == y, s"seed $raw is not referentially transparent")
    }
  }

  pureTest("split gives two states, both different from the original and from each other") {
    forEach(seeds) { raw =>
      val (left, right) = Seed(raw).split
      check(left.toLong != right.toLong, s"seed $raw: the two halves are equal")
        .and(check(left.toLong != raw || right.toLong != raw, s"seed $raw: split is the identity"))
        .and {
          val (again, _) = Seed(raw).split
          check(again.toLong == left.toLong, s"seed $raw: split is not deterministic")
        }
    }
  }

  pureTest("split streams differ from each other and from the parent") {
    forEach(seeds) { raw =>
      val (left, right) = Seed(raw).split
      val a = stream(left, 20)
      val b = stream(right, 20)
      check(a != b, s"seed $raw: the two split streams are identical")
        .and(check(a != stream(Seed(raw), 20), s"seed $raw: a split stream repeats the parent"))
    }
  }

  pureTest("fold by index gives 1000 distinct streams, deterministically") {
    val firsts = (0 until 1000).map(i => Seed(20261104L).fold(i.toLong).next._2).toList
    check(firsts.distinct.length == 1000, s"${firsts.distinct.length} distinct first draws of 1000")
      .and {
        val again = (0 until 1000).map(i => Seed(20261104L).fold(i.toLong).next._2).toList
        check(again == firsts, "fold is not deterministic")
      }
      .and {
        val other = (0 until 1000).map(i => Seed(1L).fold(i.toLong).next._2).toList
        check(other != firsts, "fold ignores the seed it starts from")
      }
  }
