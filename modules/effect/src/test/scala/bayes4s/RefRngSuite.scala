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
import cats.effect.IO
import cats.syntax.all.*
import weaver.SimpleIOSuite

/** The effectful interpreter of the randomness algebra, against the pure one.
  *
  * This is the hinge of the whole module. Everything in the core is written against `Rng[F]` and
  * interpreted by `State`; the effectful edge interprets the same algebra over a `Ref`. If the two
  * disagreed by a single bit then nothing run in `IO` would be reproducible from a pure test, and
  * the library's claim that a fit is a function of its seed would be false at the edge.
  *
  * They cannot disagree, by construction: `RefRng` runs the **same pure programs** through its
  * `Ref` rather than reimplementing them. This suite is what proves that is what it does.
  */
object RefRngSuite extends SimpleIOSuite with Approx:

  private val draws = 500

  test("every method agrees with the State interpreter, bit for bit") {
    val seed = Seed(20261107L)
    // The same program, three ways: longs, uniforms and normals, interleaved so that the order of
    // consumption matters — a `RefRng` that drew the right numbers in the wrong order would pass a
    // test of each method alone.
    def program[F[_]: cats.Monad: Rng]: F[List[Double]] =
      (0 until draws).toList.flatTraverse { i =>
        if i % 3 == 0 then Rng[F].nextLong.map(l => List(l.toDouble))
        else if i % 3 == 1 then Rng[F].uniform.map(List(_))
        else Rng[F].normal.map(List(_))
      }
    val pure = program[Rand].runA(seed).value
    for effectful <- RefRng.make[IO](seed).flatMap(rng => program[IO](using IO.asyncForIO, rng))
    yield check(pure.length == draws, s"${pure.length} values from the pure interpreter")
      .and(
        check(
          pure.zip(effectful).forall((a, b) => a == b),
          s"the two interpreters disagree: ${pure.zip(effectful).find((a, b) => a != b)}"
        )
      )
  }

  test("a split seed gives independent chains, as it does in State") {
    val seed = Seed(5L)
    def program[F[_]: cats.Monad: Rng]: F[List[Double]] =
      cats.Monad[F].replicateA(50, Rng[F].normal)
    for
      first <- RefRng.make[IO](seed.fold(0L)).flatMap(rng => program[IO](using IO.asyncForIO, rng))
      second <- RefRng.make[IO](seed.fold(1L)).flatMap(rng => program[IO](using IO.asyncForIO, rng))
    yield
      val pureFirst = program[Rand].runA(seed.fold(0L)).value
      val pureSecond = program[Rand].runA(seed.fold(1L)).value
      check(first == pureFirst, "the first split stream differs from State's")
        .and(check(second == pureSecond, "the second split stream differs from State's"))
        .and(check(first != second, "two split seeds gave the same stream"))
  }

  test("the same Ref read twice carries on rather than restarting") {
    // A `Ref` is state: the second batch has to continue the first, which is what makes a long run
    // in `IO` equal to one long pure program rather than to two short ones.
    val seed = Seed(9L)
    for
      rng <- RefRng.make[IO](seed)
      first <- rng.normal.replicateA(20)
      second <- rng.normal.replicateA(20)
    yield
      val whole = cats.Monad[Rand].replicateA(40, Rng[Rand].normal).runA(seed).value
      check(first ++ second == whole, "the two batches are not one continued stream")
        .and(check(first != second, "the second batch repeated the first"))
  }

  test("concurrent use is serialised, and consumes each value once") {
    // A `Ref` makes the generator safe to share between fibres: the order is then whatever the
    // scheduler chose, so nothing can be asserted about *which* value each fibre got — only that
    // the multiset is the one the pure interpreter would have produced, and that nothing was
    // handed out twice. That is the honest claim, and it is why a sampler gives every chain its
    // own generator instead of sharing one.
    val seed = Seed(11L)
    for
      rng <- RefRng.make[IO](seed)
      drawn <- List.fill(8)(rng.normal.replicateA(25)).parSequence
    yield
      val all = drawn.flatten
      val expected = cats.Monad[Rand].replicateA(200, Rng[Rand].normal).runA(seed).value
      check(all.length == 200, s"${all.length} values")
        .and(check(all.distinct.length == 200, "a value was handed out twice"))
        .and(
          check(
            all.sorted == expected.sorted,
            "the values are not the ones the pure interpreter would have produced"
          )
        )
  }
