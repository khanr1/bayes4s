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

package bayes4s.rethinking

import bayes4s.*
import cats.Monad
import cats.effect.Async
import cats.syntax.all.*

/** One model's place in a comparison, with the package's columns. */
final case class Comparison(
    name: String,
    criterion: Double,
    standardError: Double,
    difference: Double,
    differenceStandardError: Double,
    weight: Double
)

/** The book's workflow verbs, over the fits the core produces.
  *
  * Nothing here is a new type: a fit is `List[Chain]` or `Draws`, a summary is `List[Summary]`, a
  * cross-validation is `LooResult`. The package's names are the point; its types are not.
  *
  * **Example.** Fitting with the sampler and reading the fit the book's way:
  *
  * ```scala
  * //{
  * import bayes4s.*
  * import bayes4s.syntax.{*, given}
  * final case class Mean[A](mu: A, sigma: A)
  * val y = IArray(4.1, 5.2, 3.8, 4.9, 5.5, 4.4)
  * val model: Model[Mean] = new Model[Mean]:
  *   def params: ParamSpec[Mean] =
  *     (ParamSpec.real("mu"), ParamSpec.positive("sigma"))
  *       .mapN[Mean]([A] => (mu: A, sigma: A) => Mean(mu, sigma))
  *   def logPrior[A: Real](p: Mean[A]): A =
  *     LogDensity.normal(p.mu, 0.0.lift[A], 10.0.lift[A]) +
  *       LogDensity.halfNormal(p.sigma, 5.0.lift[A])
  *   def logLikelihood[A: Real](p: Mean[A]): A =
  *     Real[A].normalSum(y, Real[A].filled(y.length)(p.mu), p.sigma)
  * //}
  * // `ulam` is the sampler, pure and reproducible from its seed.
  * val fit: Either[BayesError, List[Chain]] = Fits.ulam(model, chains = 4, seed = Seed(42L))
  *
  * // `precis` is the summary, at the book's 89% interval.
  * val table: Either[BayesError, String] = fit.map(cs => Fits.precisText(model, cs))
  *
  * // `extractSamples` is the posterior, as a `Draws`.
  * val samples: Either[BayesError, Draws] = fit.flatMap(cs => Fits.extractSamples(model, cs))
  * ```
  */
object Fits:

  /** `ulam`: the no-U-turn sampler, as a pure function of its seed. */
  def ulam[T[_]](
      model: Model[T],
      chains: Int = 4,
      warmup: Int = 1000,
      samples: Int = 1000,
      seed: Seed = Seed(1L)
  ): Either[BayesError, List[Chain]] =
    Mcmc
      .runNuts(model, RunSettings(chains = chains, warmup = warmup, samples = samples))
      .runA(seed)
      .value

  /** The same on separate fibres, which is what the package's `cores` argument is about. */
  def ulamParallel[F[_]: Async, T[_]](
      model: Model[T],
      chains: Int = 4,
      warmup: Int = 1000,
      samples: Int = 1000,
      seed: Seed = Seed(1L)
  ): F[Draws] =
    Sampler[F].run(model, RunSettings(chains = chains, warmup = warmup, samples = samples), seed)

  /** `precis`: the summary, with the book's 89% interval rather than the core's explicit mass.
    *
    * The rows are the core's `Summary` **unchanged**, so `mean`, `sd`, `rhat`, `essBulk` and
    * `essTail` are the same numbers the rest of the library reports. It therefore takes no `prob`:
    * a `Summary` row carries the 5th, 50th and 95th percentiles and nothing else, and a function
    * that accepted a mass and ignored it would be worse than one that does not offer it.
    * [[precisText]] is where the book's 89 % interval appears, because it computes the interval
    * itself.
    */
  def precis[T[_]](model: Model[T], chains: List[Chain]): List[Summary] =
    Summary.of(model, chains)

  /** The same as the package prints it: a column per statistic, the interval at `prob`. */
  def precisText[T[_]](
      model: Model[T],
      chains: List[Chain],
      prob: Double = Rethinking.defaultProb
  ): String =
    val rows = Summary.of(model, chains)
    val draws = chains.flatMap(_.draws)
    val values = draws.map(q => Model.values(model, q))
    val low = (1.0 - prob) / 2.0
    // Every number is formatted under `Locale.ROOT`, as the core's own table is: an interpolator
    // follows the default locale, so on a French machine the columns came out as `5,5%` and the
    // suite said so.
    def fixed(x: Double, width: Int, decimals: Int): String =
      String.format(java.util.Locale.ROOT, s"%$width.${decimals}f", x)
    def percent(p: Double): String = String.format(java.util.Locale.ROOT, "%.1f%%", p * 100.0)
    val header = String.format(
      java.util.Locale.ROOT,
      "%-12s%10s%10s%10s%10s%10s%8s",
      "name",
      "mean",
      "sd",
      percent(low),
      percent(1.0 - low),
      "n_eff",
      "Rhat"
    )
    val body = rows.map { row =>
      val column = columnOf(values, row.name)
      val (lower, upper) = Intervals.equalTailed(column, prob)
      String.format(java.util.Locale.ROOT, "%-12s", row.name) +
        fixed(row.mean, 10, 2) + fixed(row.sd, 10, 2) + fixed(lower, 10, 2) + fixed(upper, 10, 2) +
        fixed(row.essBulk, 10, 0) + fixed(row.rhat, 8, 2)
    }
    (header :: body).mkString("\n")

  /** One parameter's samples, by the name `Summary` gave it — `z.1`, `z.2`, and so on. */
  private def columnOf(
      values: List[Map[String, IArray[Double]]],
      name: String
  ): IArray[Double] =
    val (block, index) = name.lastIndexOf('.') match
      case -1 => (name, 0)
      case at =>
        name.drop(at + 1).toIntOption match
          case Some(i) => (name.take(at), i - 1)
          case None => (name, 0)
    IArray.from(values.map(row => row.get(block).flatMap(_.lift(index)).getOrElse(Double.NaN)))

  /** `extract.samples`: the posterior as a `Draws`, which is what every other verb reads. */
  def extractSamples[T[_]](model: Model[T], chains: List[Chain]): Either[BayesError, Draws] =
    Mcmc.draws(model, chains)

  /** `extract.samples` of a `quap` fit: the multivariate normal the approximation describes. */
  def extractSamples[F[_]: Monad: Rng](fitted: Quap, draws: Int): F[Either[BayesError, Draws]] =
    Matrix
      .ofRows(IArray.tabulate(fitted.mode.length)(i => fitted.covariance.row(i)))
      .flatMap(Linalg.cholesky)
      .flatMap(factor => MultiNormalCholesky.of(fitted.mode, factor)) match
      case Left(error) => Monad[F].pure(Left(error))
      case Right(law) =>
        Monad[F]
          .replicateA(math.max(draws, 0), law.sample[F])
          .map(rows => Draws.of(IArray.from(fitted.names), IArray(IArray.from(rows))))

  /** `extract.prior`: the prior as a model of its own, sampled the same way. */
  def extractPrior[T[_]](
      model: Model[T],
      chains: Int = 1,
      warmup: Int = 1000,
      samples: Int = 1000,
      seed: Seed = Seed(1L)
  ): Either[BayesError, List[Chain]] =
    ulam(Model.priorOnly(model), chains, warmup, samples, seed)

  /** `link`: a model's derived quantities, one row per sample from the posterior. */
  def link[T[_]](model: Model[T], chains: List[Chain]): List[Map[String, IArray[Double]]] =
    chains.flatMap(_.draws).map(q => model.derived(Model.constrain(model, q)))

  /** `sim`: data simulated from the posterior, one dataset per sample. */
  def sim[F[_]: Monad: Rng, T[_], Y](
      generative: GenerativeModel[T, Y],
      chains: List[Chain]
  ): F[List[Y]] =
    chains
      .flatMap(_.draws)
      .traverse(q => generative.generative.simulateData[F](Model.constrain(generative.model, q)))

  /** `ensemble`: the simulations of several models, mixed in the proportions `compare` gives.
    *
    * The weights are the Akaike weights of the comparison, and the mixture takes that share of
    * each model's simulations — the book's own recipe, and the reason `compare` reports weights at
    * all.
    */
  def ensemble[Y](simulations: List[(String, List[Y])], weights: Map[String, Double]): List[Y] =
    val total = simulations.map((name, _) => weights.getOrElse(name, 0.0)).sum
    if !(total > 0.0) then Nil
    else
      simulations.flatMap { (name, drawn) =>
        val share = weights.getOrElse(name, 0.0) / total
        drawn.take(math.round(share * drawn.length).toInt)
      }

  /** `compare`: models ranked by an information criterion, with Akaike weights.
    *
    * Takes the criterion already computed — `WAIC` or `PSIS`, both on the deviance scale — so that
    * the function cannot disagree with whichever of the two the caller used. Smaller is better, so
    * the differences are measured from the smallest, and the weights are
    * `exp(-difference/2)` normalised, which is what the book calls the Akaike weight.
    *
    * The standard error of a difference comes from the **pointwise** differences, because two
    * models scored on the same observations have correlated errors: the same point `Loo.compare`
    * makes, and the same arithmetic.
    */
  def compare(models: List[(String, Waic)]): List[Comparison] =
    if models.isEmpty then Nil
    else
      val best = models.minBy((_, w) => w.waic)
      val differences = models.map { (name, w) =>
        val pointwise = IArray.tabulate(w.pointwise.length) { i =>
          w.pointwise(i) - best._2.pointwise.lift(i).getOrElse(Double.NaN)
        }
        val n = pointwise.length
        val mean = pointwise.sum / n
        val variance =
          if n < 2 then 0.0 else pointwise.map(v => (v - mean) * (v - mean)).sum / (n - 1)
        (name, w, w.waic - best._2.waic, math.sqrt(n * variance))
      }
      val weights = differences.map((_, _, d, _) => math.exp(-0.5 * d))
      val total = weights.sum
      differences
        .zip(weights)
        .map { case ((name, w, d, se), weight) =>
          Comparison(
            name,
            w.waic,
            w.standardError,
            d,
            se,
            if total > 0.0 then weight / total else Double.NaN
          )
        }
        .sortBy(_.criterion)

  /** `coeftab`: one row per parameter, one column per model, of posterior means.
    *
    * It takes the summaries rather than the models, because the models being compared rarely have
    * the same parameter type — that is the whole reason to compare them — and a signature that
    * demanded one would be a façade that cannot do what the package's does.
    */
  def coeftab(fits: List[(String, List[Summary])]): Map[String, Map[String, Double]] =
    fits.foldLeft(Map.empty[String, Map[String, Double]]) { case (table, (name, rows)) =>
      rows.foldLeft(table) { (sofar, row) =>
        sofar.updatedWith(row.name) {
          case Some(byModel) => Some(byModel + (name -> row.mean))
          case None => Some(Map(name -> row.mean))
        }
      }
    }
