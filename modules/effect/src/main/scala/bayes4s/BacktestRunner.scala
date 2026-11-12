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

import cats.Monad
import cats.syntax.all.*
import fs2.Stream

/** The half of a backtest phase 8 deliberately left out: the loop over origins.
  *
  * Phase 8's `Backtest.run` scores forecasts it is handed, and says so — refitting at each origin
  * needs a model and a sampler, which `scoring` does not depend on. This is where that loop lives,
  * and it is a **stream** for a reason: a backtest refits the model once per origin, which is the
  * most expensive thing in the library, and a stream lets the rows be watched as they arrive,
  * written out one at a time, and stopped after any of them.
  *
  * The forecast is the caller's: a function from an origin to an ensemble in `F`, which is where
  * the refit happens. The runner owns only the loop, the ordering and the scoring, so nothing in
  * it knows what a model is.
  *
  * **No fit may see its own target.** The runner cannot enforce that — the forecast function is
  * opaque to it — so it is the caller's contract, and chapter 8 says what happens when it is
  * broken: every score flatters the model and the comparison means nothing.
  * **Example.** A backtest refits once per origin — the most expensive thing this library does — so
  * the rows are a stream: they can be watched as they arrive, written out one at a time, and stopped
  * after any of them.
  *
  * ```scala
  * //{
  * import cats.effect.IO
  * //}
  * // The forecast is yours, which is why the runner knows nothing about models — and why the
  * // contract that no fit may see its own target stays yours too.
  * def forecastFrom(origin: Int): IO[IArray[Double]] =
  *   IO.pure(IArray.tabulate(500)(i => 100.0 + origin + i * 0.01))
  *
  * val rows: fs2.Stream[IO, Scored[Int]] =
  *   BacktestRunner[IO].run(
  *     origins = (1 to 24).toList,
  *     forecast = forecastFrom,
  *     observed = origin => 100.0 + origin + 0.5,
  *     alpha = 0.1
  *   )
  *
  * // Taking two rows costs two refits, not twenty-four.
  * val firstTwo: IO[List[Scored[Int]]] = rows.take(2).compile.toList
  * ```
  */
trait BacktestRunner[F[_]]:

  def run[D](
      origins: List[D],
      forecast: D => F[IArray[Double]],
      observed: D => Double,
      alpha: Double
  ): Stream[F, Scored[D]]

object BacktestRunner:

  def apply[F[_]: Monad]: BacktestRunner[F] = new BacktestRunner[F]:

    def run[D](
        origins: List[D],
        forecast: D => F[IArray[Double]],
        observed: D => Double,
        alpha: Double
    ): Stream[F, Scored[D]] =
      // `evalMap` over the origins: one refit at a time, in order, and the next one is not started
      // until the previous row has been emitted. That is what makes `take` cheap.
      Stream
        .emits(origins)
        .evalMap(origin =>
          forecast(origin).map(ensemble =>
            Backtest.run(List((origin, ensemble, observed(origin))), alpha).scored.head
          )
        )
