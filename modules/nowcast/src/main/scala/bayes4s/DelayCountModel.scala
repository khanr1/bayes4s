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

import bayes4s.syntax.{*, given}
import cats.Monad
import cats.syntax.all.*

/** Counts by creation period and reporting delay, with the mask of what has been seen.
  *
  * `counts(group)(month)(delay)` is how many events created in `month` were reported `delay`
  * periods later. The cells outside `visible` are not zero — they are **unknown**, which is the
  * whole difficulty — and nothing reads them.
  *
  * `t` is the time covariate, one value per month, on whatever scale the application chose; the
  * trend and the delay drift are both linear in it, so centring it is the caller's job and the
  * manual says why.
  */
final case class DelayCountData private (
    groups: Int,
    months: Int,
    maxLag: Int,
    t: IArray[Double],
    counts: IArray[IArray[IArray[Int]]],
    visible: ReportingTriangle
):

  /** The cells the likelihood reads: group, month, delay, count. */
  val cells: IArray[(Int, Int, Int, Int)] =
    IArray.from(
      for
        g <- 0 until groups
        m <- 0 until months
        k <- 0 to maxLag
        if visible.isVisible(m, k)
      yield (g, m, k, counts(g)(m)(k))
    )

  def visibleTotal: Int = cells.toList.map((_, _, _, y) => y).sum

object DelayCountData:

  def of(
      t: IArray[Double],
      counts: IArray[IArray[IArray[Int]]],
      visible: ReportingTriangle
  ): Either[BayesError, DelayCountData] =
    val groups = counts.length
    if groups == 0 then
      Left(BayesError.InvalidArgument("DelayCountData.of", "counts", 0.0, "at least one group"))
    else
      val months = counts(0).length
      val maxLag = if months == 0 then -1 else counts(0)(0).length - 1
      if months != t.length then
        Left(BayesError.DimensionMismatch("DelayCountData.of, t", months, t.length))
      else if counts.exists(_.length != months) then
        Left(BayesError.DimensionMismatch("DelayCountData.of, months", months, -1))
      else if counts.exists(_.exists(_.length != maxLag + 1)) then
        Left(BayesError.DimensionMismatch("DelayCountData.of, delays", maxLag + 1, -1))
      else if visible.creations != months then
        Left(
          BayesError.DimensionMismatch("DelayCountData.of, mask months", months, visible.creations)
        )
      else if visible.delays != maxLag + 1 then
        Left(
          BayesError.DimensionMismatch("DelayCountData.of, mask delays", maxLag + 1, visible.delays)
        )
      else Right(DelayCountData(groups, months, maxLag, t, counts, visible))

  /** The same shape with no counts in it, which is what a simulation fills. */
  def blank(months: Int, maxLag: Int, t: IArray[Double], visible: ReportingTriangle) =
    of(t, IArray(IArray.tabulate(months)(_ => IArray.fill(maxLag + 1)(0))), visible)

/** The two components' parameters, side by side. */
final case class DelayCountParams[R[_], D[_], A](rate: R[A], delay: D[A])

/** The count model: a rate component, a delay component, and the cells that were reported.
  *
  * {{{
  * log lambda[g, m] = rate.logRate(g, m)
  * p[g, m](k)       = exp(delay.logPmf(g, t[m]))[k]
  * y[g, m, k] ~ Poisson(lambda[g, m] * p[g, m](k))   for the visible cells only
  * }}}
  *
  * The likelihood never leaves the log scale: the Poisson's log mass is taken at
  * `logRate + logPmf`, so a rate of a hundred thousand and a delay probability of 1e-12 multiply
  * without either of them being formed.
  *
  * Changing the story about the rate, or about the delay, means passing a different component. The
  * model itself does not know which one it has.
  * **Example.** A count model is a rate component, a delay component and the data. Trying a different
  * story about the rate means passing a different component, not rewriting the model:
  *
  * ```scala
  * //{
  * val months = 18
  * val maxLag = 5
  * val t = IArray.tabulate(months)(m => (m - (months - 1) / 2.0) / months)
  * val visible = ReportingTriangle.rightCensored(months, maxLag + 1, current = months)
  * //}
  * // An empty grid of the right shape. It can be refused — a mask that disagrees with the counts
  * // — so it is an `Either`, and everything below reads it through `map`.
  * val blank = DelayCountData.blank(months, maxLag, t, visible)
  *
  * val fit: Either[BayesError, List[Chain]] = blank.flatMap { data =>
  *   val model = DelayCountModel(Ar1Trend(), OrderedLogitDrift(), data)
  *   Mcmc.runNuts(model, RunSettings(chains = 2, warmup = 600, samples = 600))
  *     .runA(Seed(4711L))
  *     .value
  * }
  *
  * // `generative` adds the other direction — draw parameters from the prior, simulate counts from
  * // them — which is what a prior predictive simulation and calibration need.
  * val simulated: Either[BayesError, DelayCountData] = blank.map { data =>
  *   val generative = DelayCountModel.generative(Ar1Trend(), OrderedLogitDrift(), data)
  *   generative.generative.samplePrior[Rand]
  *     .flatMap(p => generative.generative.simulateData[Rand](p))
  *     .runA(Seed(1L))
  *     .value
  * }
  * ```
  */
final case class DelayCountModel[R[_], D[_]](
    rate: RateComponent[R],
    delay: DelayComponent[D],
    data: DelayCountData
) extends Model[[A] =>> DelayCountParams[R, D, A]]:

  val params: ParamSpec[[A] =>> DelayCountParams[R, D, A]] =
    (rate.params(data.groups, data.months), delay.params(data.groups, data.maxLag))
      .mapN[[A] =>> DelayCountParams[R, D, A]]([A] =>
        (r: R[A], d: D[A]) => DelayCountParams[R, D, A](r, d)
      )

  def logPrior[A: Real](p: DelayCountParams[R, D, A]): A =
    rate.logPrior(p.rate) + delay.logPrior(p.delay)

  def logLikelihood[A: Real](p: DelayCountParams[R, D, A]): A =
    val r = Real[A]
    val perGroup = (0 until data.groups).map { g =>
      val logRate = rate.logRate(p.rate, g, data.t)
      val perMonth = (0 until data.months).map { m =>
        val logPmf = delay.logPmf(p.delay, g, data.t(m))
        val visible = (0 to data.maxLag).filter(k => data.visible.isVisible(m, k))
        if visible.isEmpty then r.const(0.0)
        else
          val ks = IArray.from(visible.map(k => data.counts(g)(m)(k)))
          val logRates = r.vectorOf(visible.length)(i => logRate(m) + logPmf(visible(i)))
          r.poissonLogSum(ks, logRates)
      }
      perMonth.foldLeft(r.const(0.0))(_ + _)
    }
    perGroup.foldLeft(r.const(0.0))(_ + _)

  /** The rate itself, which is what a nowcast reports: `groups * months` values, group by group. */
  override def derived(p: DelayCountParams[R, D, Double]): Map[String, IArray[Double]] =
    val lambda = (0 until data.groups).flatMap { g =>
      val logRate = rate.logRate[Double](p.rate, g, data.t)
      (0 until data.months).map(m => math.exp(logRate(m)))
    }
    Map("lambda" -> IArray.from(lambda))

object DelayCountModel:

  /** The same model read as a program that generates data, for calibration and prior checks.
    *
    * The parameters come from the components' own priors and the counts from thinning a Poisson
    * rate by the delay distribution of that month — `PoissonThinning`, one month at a time,
    * because the delay distribution drifts and so differs from month to month.
    */
  def generative[R[_], D[_]](
      rate: RateComponent[R],
      delay: DelayComponent[D],
      blank: DelayCountData
  ): GenerativeModel[[A] =>> DelayCountParams[R, D, A], DelayCountData] =
    val generativePart = new Generative[[A] =>> DelayCountParams[R, D, A], DelayCountData]:

      def samplePrior[F[_]: Monad: Rng]: F[DelayCountParams[R, D, Double]] =
        for
          r <- rate.samplePrior[F](blank.groups, blank.months)
          d <- delay.samplePrior[F](blank.groups, blank.maxLag)
        yield DelayCountParams[R, D, Double](r, d)

      def simulateData[F[_]: Monad: Rng](
          p: DelayCountParams[R, D, Double]
      ): F[DelayCountData] =
        (0 until blank.groups).toList
          .traverse { g =>
            val logRate = rate.logRate[Double](p.rate, g, blank.t)
            (0 until blank.months).toList.traverse { m =>
              val pmf = delay.logPmf[Double](p.delay, g, blank.t(m))
              val shares = IArray.tabulate(pmf.length)(k => math.exp(pmf(k)))
              PoissonThinning
                .simulate[F](IArray(math.exp(logRate(m))), shares, firstPeriodFactor = 1.0)
                .map(grid => grid(0))
            }
          }
          .map { drawn =>
            val counts = IArray.from(drawn.map(months => IArray.from(months)))
            DelayCountData.of(blank.t, counts, blank.visible).getOrElse(blank)
          }

      /** The parameters calibration ranks: whichever ones the two components name.
        *
        * Each component decides for itself, which is both extensible — a new component says what
        * it names — and the only way to do it without a type test on an erased parameter.
        */
      def named(p: DelayCountParams[R, D, Double]): Map[String, IArray[Double]] =
        rate.named(p.rate) ++ delay.named(p.delay)

    GenerativeModel(blank, data => DelayCountModel(rate, delay, data), generativePart)
