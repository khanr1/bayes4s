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
import cats.Monad
import cats.effect.IO
import cats.syntax.all.*
import weaver.SimpleIOSuite

/** The batch samplers against the `State` programs they replace.
  *
  * `Batch` exists only for speed: it fills arrays in place where the `State` path allocates. The
  * two must therefore draw the same numbers from the same seed, in the same order, and this
  * equality is what forbids them from drifting apart. It is not a correctness oracle — that comes
  * from the moments and the distribution tests of `RngSuite` — it is the guarantee that one path
  * cannot silently become a different sampler from the other.
  */
object BatchSuite extends SimpleIOSuite with Approx:

  private val seed = Seed(20261104L)

  private def run[A](program: Rand[A], from: Seed): A = program.runA(from).value

  pureTest("Batch.normal draws exactly what the State program draws") {
    forEach(List(0, 1, 2, 7, 64, 1000)) { count =>
      val batch = Batch.normal(seed, count).toList
      val state = run(Monad[Rand].replicateA(count, Rng[Rand].normal), seed)
      check(
        batch == state,
        s"n = $count: the first difference is at ${batch.zip(state).indexWhere((a, b) => a != b)}"
      )
    }
  }

  pureTest("Batch.poisson draws exactly what the State program draws") {
    val means = IArray(0.0, 0.1, 1.0, 9.5, 10.5, 100.0, 1e4)
    val batch = Batch.poisson(seed, means).toList
    val state = run(means.toList.traverse(m => Sampling.poisson[Rand](m)), seed)
    check(batch == state, s"batch gave $batch, the State program gave $state")
  }

  // Against the alias program, not the cumulative one: the two are different samplers that agree
  // in distribution and not draw by draw, and it is the algorithm that must be shared here.
  pureTest("Batch.categorical draws exactly what the State alias program draws") {
    val weights = IArray(0.1, 0.2, 0.05, 0.4, 0.25)
    AliasTable.of(weights) match
      case Left(error) => failure(error.message)
      case Right(table) =>
        val batch = Batch.categorical(seed, table, 500).toList
        val state = run(Monad[Rand].replicateA(500, Sampling.alias[Rand](table)), seed)
        check(batch == state, "the batch and State alias paths disagree")
  }

  pureTest("an empty batch is empty, and a negative count is empty rather than an error") {
    check(Batch.normal(seed, 0).length == 0, "n = 0")
      .and(check(Batch.normal(seed, -1).length == 0, "n = -1"))
      .and(check(Batch.poisson(seed, IArray.empty[Double]).length == 0, "no means"))
  }

  // The batch fills a mutable array inside a pure function. From the outside it must be a value.
  pureTest("the batch samplers are referentially transparent and leave their input alone") {
    val means = IArray(1.0, 2.0, 3.0)
    val before = means.toList
    val first = Batch.poisson(seed, means).toList
    val second = Batch.poisson(seed, means).toList
    check(first == second, "two calls with the same seed disagree")
      .and(check(means.toList == before, "the means were modified"))
      .and(check(Batch.normal(seed, 10).toList == Batch.normal(seed, 10).toList, "normal drifted"))
      .and(
        check(
          Batch.normal(seed, 10).toList != Batch.normal(Seed(1L), 10).toList,
          "two seeds gave the same batch"
        )
      )
  }

  test("a batch computed in parallel equals the same batch computed in sequence") {
    val indices = (0 until 32).toList
    for
      sequential <- IO(indices.map(i => Batch.normal(seed.fold(i.toLong), 100).toList))
      parallel <- indices.parTraverse(i => IO(Batch.normal(seed.fold(i.toLong), 100).toList))
    yield check(parallel == sequential, "the parallel batches differ")
  }

  pureTest("the alias table refuses weights it cannot use") {
    check(AliasTable.of(IArray.empty[Double]).isLeft, "no weights")
      .and(check(AliasTable.of(IArray(0.0, 0.0)).isLeft, "all weights zero"))
      .and(check(AliasTable.of(IArray(1.0, -1.0)).isLeft, "a negative weight"))
      .and(check(AliasTable.of(IArray(1.0, Double.NaN)).isLeft, "a NaN weight"))
      .and(check(AliasTable.of(IArray(1.0, Double.PositiveInfinity)).isLeft, "an infinite weight"))
      .and(check(AliasTable.of(IArray(2.0, 3.0)).isRight, "two positive weights are fine"))
  }
