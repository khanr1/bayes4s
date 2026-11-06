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

/** How big one simulated event is.
  *
  * The application's own story, because a size is about the thing being counted and the library
  * knows nothing about that. The log-normal one is provided because it is the usual answer: sizes
  * are right-skewed, they trend, and the ones reported late are often not the size of the ones
  * reported at once.
  */
trait SizeModel[P]:
  def draw[F[_]: Monad: Rng](payload: P, t: Double, lag: Int): F[Double]

object SizeModel:

  /** Every event the same size, which is what a pure count forecast wants. */
  def unit[P]: SizeModel[P] = new SizeModel[P]:
    def draw[F[_]: Monad: Rng](payload: P, t: Double, lag: Int): F[Double] = Monad[F].pure(1.0)

  /** `exp(Gaussian(mu + kappa t + omega log1p(lag), s))`, with an optional cap.
    *
    * The cap is a parameter and not a default. A log-normal has no largest value, so a long
    * simulation will eventually draw something absurd — and an absurd single event can dominate a
    * total and make an interval meaningless. Capping is a statement that sizes above some value do
    * not happen in this application; leaving it off is a statement that they do.
    */
  def logNormal[P](
      p: LogNormalTrendRegressionParams[Double],
      cap: Option[Double] = None
  ): SizeModel[P] =
    new SizeModel[P]:
      def draw[F[_]: Monad: Rng](payload: P, t: Double, lag: Int): F[Double] =
        Rng[F].normal.map { z =>
          val drawn = math.exp(p.mu + p.kappa * t + p.omega * math.log1p(lag.toDouble) + p.s * z)
          cap.fold(drawn)(limit => math.min(drawn, limit))
        }

/** What a forecast run is asked for. */
final case class ForecastSettings(
    perDraw: Int = 1,
    firstPeriodFactor: Double = 1.0
)

/** The predictive simulation: the fit, carried into periods it has not seen.
  *
  * One simulated future per sample from the posterior (or `perDraw` of them), each assembling the
  * pieces of the earlier phases: the rate component's own projection, the delay component's drift,
  * a Poisson count thinned by that delay, a template drawn from a bootstrap pool, a size, and the
  * application's contribution rule to place what results on the period axis.
  *
  * Everything a future needs comes from **one** sample: the rate, the delay, the template weights.
  * That is what keeps the correlations the fit found, and it is why the seed is derived per future
  * rather than per draw of anything.
  */
object Forecast:

  def run[R[_], D[_], P, K](
      rate: RateComponent[R],
      delay: DelayComponent[D],
      parameters: List[DelayCountParams[R, D, Double]],
      futureT: IArray[Double],
      periods: Periods,
      pool: TemplatePool[P],
      size: SizeModel[P],
      rule: ContributionRule[P, K],
      settings: ForecastSettings,
      seed: Seed
  ): Ensemble[K] =
    byGroup(rate, delay, parameters, futureT, periods, pool, size, 1, _ => rule, settings, seed)

  /** The same, for a model with several groups: one rule per group.
    *
    * Every group is simulated inside the **same** future, from the same sample from the posterior,
    * so what the groups share — the AR(1) wobble, the delay's shape, the hyperparameters — is
    * shared in the simulation too. Forecasting the groups separately and adding the results
    * afterwards would break exactly that, and the correlation between groups would come out as
    * zero when the model says it is not.
    *
    * The rule is a function of the group so that contributions can be keyed by it: pass
    * `g => (payload, scaled) => List(Contribution(label(g), 0, scaled))` to get an ensemble per
    * group, whose `total` is the forecast of the whole — summed sample by sample, never by adding
    * quantiles.
    */
  def byGroup[R[_], D[_], P, K](
      rate: RateComponent[R],
      delay: DelayComponent[D],
      parameters: List[DelayCountParams[R, D, Double]],
      futureT: IArray[Double],
      periods: Periods,
      pool: TemplatePool[P],
      size: SizeModel[P],
      groups: Int,
      rule: Int => ContributionRule[P, K],
      settings: ForecastSettings,
      seed: Seed
  ): Ensemble[K] =
    val repeated = parameters.flatMap(p => List.fill(math.max(settings.perDraw, 1))(p))
    if repeated.isEmpty || futureT.length == 0 || groups < 1 then Ensemble.empty[K]
    else
      val simulator = new Simulator[DelayCountParams[R, D, Double], K]:
        def simulate(draw: DelayCountParams[R, D, Double], futureSeed: Seed): Ensemble[K] =
          val program = (0 until groups).toList
            .traverse { g =>
              val logRate = rate.project(draw.rate, g, futureT, futureSeed.fold(g.toLong))
              (0 until futureT.length).toList
                .traverse { h =>
                  // The first period of a forecast is usually partial: a month that has already
                  // started contributes only the share of itself that is left.
                  val factor = if h == 0 then settings.firstPeriodFactor else 1.0
                  val expected = math.exp(logRate(h)) * factor
                  val pmf = delay.logPmf[Double](draw.delay, g, futureT(h))
                  for
                    count <- Sampling.poisson[Rand](expected)
                    events <- (0 until count).toList.traverse { _ =>
                      for
                        payload <- pool.draw[Rand]
                        lag <- Sampling.categorical[Rand](cumulative(pmf))
                        amount <- size.draw[Rand](payload, futureT(h), lag)
                      yield (g, h, lag, payload, amount)
                    }
                  yield events
                }
                .map(_.flatten)
            }
            .map(_.flatten)
          val events = program.runA(futureSeed.fold(7919L)).value
          events
            .foldLeft(SimulatedFuture.empty[K](periods)) { (future, event) =>
              val (g, h, lag, payload, amount) = event
              // The event closes in period `h` and is reported `lag` later: the rule says what it
              // contributes and where, relative to the close.
              future.add(
                h,
                rule(g)
                  .contributions(payload, amount)
                  .map(c => c.copy(periodOffset = c.periodOffset + lag))
              )
            }
            .ensemble
      Simulator.run(simulator, IArray.from(repeated), seed)

  /** The cumulative distribution of a log pmf, which is what the alias-free sampler reads. */
  private def cumulative(logPmf: IArray[Double]): IArray[Double] =
    val running = (0 until logPmf.length).foldLeft((Vector.empty[Double], 0.0)) {
      case ((collected, total), k) =>
        val next = total + math.exp(logPmf(k))
        (collected :+ next, next)
    }
    IArray.from(running._1)
