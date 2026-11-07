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
import cats.effect.kernel.Ref
import weaver.SimpleIOSuite

/** The loop phase 8 left out: refit at each origin, score, stream the row.
  *
  * Why a stream rather than a list. A backtest refits the model once per origin, which is the most
  * expensive thing this library does — a twenty-origin backtest of a real model is twenty fits. A
  * stream lets the rows be watched as they arrive, written out one at a time, and **stopped** after
  * any of them, and that last property is the one with a test: taking two rows must cost two
  * refits, not twenty.
  */
object BacktestRunnerSuite extends SimpleIOSuite with Approx:

  private val origins = (1 to 6).toList

  /** A forecast that counts how many times it was asked, so laziness can be measured. */
  private def counting(counter: Ref[IO, Int]): Int => IO[IArray[Double]] =
    origin => counter.update(_ + 1).as(IArray.tabulate(200)(i => origin + i * 0.01))

  private val observed: Int => Double = origin => origin + 1.0

  test("one row per origin, in order, each scored as phase 8 scores it") {
    for
      counter <- Ref.of[IO, Int](0)
      rows <- BacktestRunner[IO].run(origins, counting(counter), observed, 0.1).compile.toList
      asked <- counter.get
    yield
      val expected = origins.map { origin =>
        val ensemble = IArray.tabulate(200)(i => origin + i * 0.01)
        Backtest.run(List((origin, ensemble, observed(origin))), 0.1).scored.head
      }
      check(rows.length == 6, s"${rows.length} rows")
        .and(check(rows.map(_.date) == origins, s"the rows are in the order ${rows.map(_.date)}"))
        .and(check(asked == 6, s"the forecast was asked $asked times for six origins"))
        // Every field comes from phase 8, so the runner adds a loop and nothing else.
        .and(
          check(
            rows.zip(expected).forall { (got, want) =>
              got.crps == want.crps && got.pit == want.pit && got.bias == want.bias &&
              got.intervalScore == want.intervalScore && got.covered == want.covered &&
              got.lower == want.lower && got.upper == want.upper
            },
            "a row differs from what Backtest.run would have scored"
          )
        )
  }

  test("taking two rows costs two refits, not six") {
    // The whole reason this is a stream. A list would have fitted every origin before the caller
    // saw the first row.
    for
      counter <- Ref.of[IO, Int](0)
      rows <- BacktestRunner[IO]
        .run(origins, counting(counter), observed, 0.1)
        .take(2)
        .compile
        .toList
      asked <- counter.get
    yield check(rows.length == 2, s"${rows.length} rows")
      .and(check(asked == 2, s"the forecast was asked $asked times for two rows"))
      .and(check(rows.map(_.date) == List(1, 2), s"the rows are ${rows.map(_.date)}"))
  }

  test("an error in a refit stops the stream and is not swallowed") {
    val failing: Int => IO[IArray[Double]] =
      origin =>
        if origin == 3 then
          IO.raiseError(BayesError.NumericalOverflow(s"the fit at origin $origin diverged"))
        else IO.pure(IArray.tabulate(50)(i => origin + i * 0.1))
    for
      attempted <- BacktestRunner[IO].run(origins, failing, observed, 0.1).compile.toList.attempt
      before <- BacktestRunner[IO].run(origins, failing, observed, 0.1).take(2).compile.toList
    yield check(attempted.isLeft, "a failing refit produced a complete backtest")
      .and(
        attempted match
          case Left(error: BayesError) =>
            check(
              error.message.contains("origin 3"),
              s"the error does not name the origin: ${error.message}"
            )
          case Left(other) => failure(s"the error is not a BayesError: $other")
          case Right(_) => failure("a failing refit produced a complete backtest")
      )
      // The rows before the failure are still obtainable: a stream that fails at origin three has
      // already emitted one and two.
      .and(
        check(
          before.map(_.date) == List(1, 2),
          s"the rows before the failure are ${before.map(_.date)}"
        )
      )
  }

  test("no origins is an empty stream, not an error") {
    for
      counter <- Ref.of[IO, Int](0)
      rows <- BacktestRunner[IO]
        .run(List.empty[Int], counting(counter), observed, 0.1)
        .compile
        .toList
      asked <- counter.get
    yield check(rows.isEmpty, s"${rows.length} rows from no origins")
      .and(check(asked == 0, "the forecast was asked about an origin that does not exist"))
  }
