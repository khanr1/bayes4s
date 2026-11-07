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

package bayes4s.examples

import bayes4s.*
import bayes4s.syntax.*
import cats.Monad
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.all.*
import fs2.io.file.Path
import java.time.YearMonth

/** The counts the demonstration's models are fitted to: one per city and month, flattened. */
final case class CityMonths(
    cities: Int,
    months: Int,
    y: IArray[Int],
    city: IArray[Int],
    month: IArray[Int]
):
  def rows: Int = y.length

  /** The same shape with other counts in it, which is what a simulation fills. */
  def withCounts(counts: IArray[Int]): CityMonths = copy(y = counts)

  /** Applications per month, every city together: what a forecast is about. */
  def perMonth: IArray[Double] =
    IArray.tabulate(months)(m =>
      (0 until rows).filter(i => month(i) == m).map(i => y(i)).sum.toDouble
    )

object CityMonths:

  /** The first `upTo` months of a world, as a panel. */
  def of(world: World, upTo: Int): CityMonths =
    val months = math.min(math.max(upTo, 0), world.months)
    val cities = City.values.length
    CityMonths(
      cities,
      months,
      IArray.tabulate(cities * months)(i => world.counts(i / months)(i % months)),
      IArray.tabulate(cities * months)(i => i / months),
      IArray.tabulate(cities * months)(i => i % months)
    )

/** One rate for every city: the model partial pooling is compared against. */
final case class OneRate[A](level: A, dispersion: A)

/** A rate per city, drawn from a population above them. */
final case class ByCity[A](mu: A, tau: A, dispersion: A, z: IArray[A])

/** Every figure of the catalogue, on the invented world.
  *
  * One page, drawn from two fits and one calibration run, and nothing in it is dressed up: the
  * figures are whatever the models say. That is the point of a demonstration page — it is a
  * regression test with a reader, and a figure that stops making sense is visible immediately.
  *
  * The work is a **pure function** of its settings: [[figures]] returns the page's figures as
  * values, and the `IOApp` around it only writes them. The suite can therefore validate every
  * figure of the real page against Vega-Lite's schema without a browser, and the page in the
  * manual is the page the suite checked.
  *
  * The models are deliberately small. A gamma-Poisson likelihood with a rate per city is enough
  * to need every figure — pooling to compare, dispersion to be sensitive to, a held-out year to
  * forecast — and small enough that the whole page is built in seconds.
  */
object CatalogueDemo extends IOApp:

  /** How much work the page is worth. The defaults are what the published page is drawn with. */
  final case class Settings(
      world: FictionalWorld.Settings = FictionalWorld.Settings(),
      fitted: Int = 108,
      run: RunSettings = RunSettings(chains = 2, warmup = 500, samples = 500),
      calibration: RunSettings = RunSettings(chains = 1, warmup = 300, samples = 300),
      replications: Int = 24,
      thinnedDraws: Int = 15,
      replicates: Int = 200,
      start: YearMonth = YearMonth.of(2020, 1),
      seed: Seed = Seed(20261107L)
  )

  /** The reduced settings CI runs the example under: the same page, drawn from fewer samples. */
  val quick: Settings = Settings(
    world = FictionalWorld.Settings(months = 36),
    fitted = 30,
    run = RunSettings(chains = 2, warmup = 150, samples = 150),
    calibration = RunSettings(chains = 1, warmup = 100, samples = 100),
    replications = 8,
    thinnedDraws = 7,
    replicates = 50
  )

  def run(args: List[String]): IO[ExitCode] =
    val path = Path(args.headOption.getOrElse("docs/target/catalogue.html"))
    val settings = if args.contains("--quick") then quick else Settings()
    figures(settings) match
      case Left(error) => IO.println(s"the page could not be built: $error").as(ExitCode.Error)
      case Right(drawn) =>
        PlotFile[IO]
          .writeReport(path, drawn, title, intro)
          .flatMap(_ => IO.println(s"${drawn.length} figures written to $path"))
          .as(ExitCode.Success)

  val title: String = "bayes4s — the plot catalogue"

  val intro: String =
    "Every figure the catalogue draws, on an invented world: a lending library in four invented " +
      "cities whose membership applications arrive at a drifting rate. The data is simulated, the " +
      "fits are real, and the figures are whatever the models say."

  /** The page, as values. `Left` only if a fit or a derived quantity failed. */
  def figures(settings: Settings): Either[BayesError, List[Figure]] =
    val world = FictionalWorld.simulate(settings.world).runA(settings.seed).value
    val fitted = math.min(math.max(settings.fitted, 1), world.months)
    val panel = CityMonths.of(world, fitted)
    val anchor = math.log(math.max(panel.y.sum.toDouble / math.max(panel.rows, 1), 1e-3))
    val byCity = varying(panel, anchor)
    val oneRate = pooled(panel, anchor)
    for
      varyingChains <- Mcmc.runNuts(byCity, settings.run).runA(settings.seed.fold(1L)).value
      pooledChains <- Mcmc.runNuts(oneRate, settings.run).runA(settings.seed.fold(2L)).value
      ranks <- Sbc
        .run(
          generative(panel, anchor),
          settings.calibration,
          settings.replications,
          settings.thinnedDraws
        )
        .runA(settings.seed.fold(3L))
        .value
      future <- futures(world, panel, byCity, varyingChains, settings)
    yield
      val draws = varyingChains.flatMap(_.draws)
      val chainsOf = (name: String, index: Int) =>
        IArray.from(
          varyingChains.map(c =>
            IArray.from(c.draws.map(q => Model.values(byCity, q)(name)(index)))
          )
        )
      val divergent =
        IArray.from(varyingChains.map(c => IArray.from(c.transitions.map(_.divergent))))
      val report = SamplerReport.of(varyingChains, settings.run.nuts.maxTreeDepth)
      val summaries = Summary.of(byCity, varyingChains)
      val looByCity = crossValidate(byCity, varyingChains)
      val looOneRate = crossValidate(oneRate, pooledChains)
      val comparison = Loo.compare(List("a rate per city" -> looByCity, "one rate" -> looOneRate))
      val cityRates = IArray.tabulate(panel.cities) { c =>
        draws.map(q => byCity.derived(Model.constrain(byCity, q))("logRate")(c)).sum / draws.length
      }
      val observed = panel.perMonth
      List(
        figure(
          "A trace, with divergent transitions marked",
          "Two chains over the population mean of the log rate. Overlapping chains and no " +
            "divergent transition is what a fit should look like before anything else is read.",
          Catalogue.tracePlot(chainsOf("mu", 0), "mu", Some(divergent))
        ),
        figure(
          "The same chains, ranked",
          "A trank plot replaces the value by its rank among the pooled samples. Chains that " +
            "disagree about the middle of the posterior show as unequal histograms, which a " +
            "trace plot of a skewed parameter hides.",
          Catalogue.trankPlot(chainsOf("tau", 0), "tau")
        ),
        figure(
          "Autocorrelation",
          "How far apart two samples have to be before they stop telling you the same thing. " +
            "The effective sample size in the summary is this picture as one number.",
          Catalogue.autocorrelationPlot(chainsOf("dispersion", 0), "dispersion")
        ),
        figure(
          "The energy of the Hamiltonian",
          s"The marginal and the transition energies of the first chain, with E-BFMI " +
            f"${report.eBfmi.headOption.getOrElse(Double.NaN)}%.2f. Below 0.3 the chain is not " +
            "exploring the tails, whatever the other diagnostics say.",
          Catalogue.energyPlot(
            IArray.from(varyingChains.headOption.toList.flatMap(_.transitions.map(_.energy))),
            report.eBfmi.headOption.getOrElse(Double.NaN)
          )
        ),
        figure(
          "Two parameters against each other",
          "The population mean against the population spread, with divergent transitions in " +
            "orange. A funnel with divergences in its neck is the picture that says to " +
            "reparameterise; this one has neither.",
          Catalogue.pairsPlot(
            IArray.from(draws.map(q => Model.values(byCity, q)("mu")(0))),
            IArray.from(draws.map(q => Model.values(byCity, q)("tau")(0))),
            ("mu", "tau"),
            Some(IArray.from(varyingChains.flatMap(_.transitions.map(_.divergent))))
          )
        ),
        figure(
          "A density per chain",
          "The posterior of the population spread, one line per chain, with the 90% " +
            "compatibility interval shaded under the pooled density. The density is reflected at " +
            "zero because the parameter cannot be negative, which is what keeps the mass from " +
            "leaking across the boundary.",
          Catalogue.densityPlot(chainsOf("tau", 0), "tau", reflectAt = Some(0.0))
        ),
        figure(
          "The summary, as a picture",
          "Each parameter's median and its 90% compatibility interval. The four city rates are " +
            "the derived quantities of the fit rather than declared parameters.",
          Catalogue.intervalPlot(
            summaries.map(s => (s.name, s.q50, s.q05, s.q95)),
            "The posterior, parameter by parameter"
          )
        ),
        figure(
          "Two models, cross-validated",
          "Expected log predictive density by importance-sampling leave-one-out, each model " +
            "against the best, with two standard errors of the difference. A rate per city is " +
            "preferred: the cities of this world really do differ.",
          Catalogue.compareModelsPlot(comparison)
        ),
        figure(
          "The shape parameter of the importance weights",
          "One Pareto shape per observation. Above 0.7 the leave-one-out posterior is too far " +
            "from the full one for reweighting to reach it, and the estimate for that " +
            "observation is not to be believed.",
          Catalogue.paretoKPlot(looByCity)
        ),
        figure(
          "A posterior predictive check",
          "Applications per month, observed against replicated: the band is where the fitted " +
            "model says the data should have been. A model with no month-to-month memory cannot " +
            "follow the drift of this world, and the check is where that shows.",
          Catalogue.predictivePlot(observed, future.replicated, 0.9, "Applications per month")
        ),
        figure(
          "The probability integral transform",
          "Each month's observation turned into its quantile under the model's predictive " +
            "distribution, as a cumulative function with a simultaneous 95% band. A calibrated " +
            "model gives a straight line inside the band.",
          Catalogue.pitPlot(future.pits)
        ),
        figure(
          "Simulation-based calibration",
          s"${settings.replications} rounds of: draw the parameters from the prior, simulate a " +
            "world from them, fit the model to it, and rank the drawn value among the samples. " +
            "The ranks must be uniform — of the prior, the simulator, the Jacobians and the " +
            "sampler together, which nothing else tests at once.",
          Catalogue.sbcPlot(ranks.ranks.getOrElse("mu", IArray.empty), ranks.thinnedDraws)
        ),
        figure(
          "Sensitivity to the prior and to the likelihood",
          "Power scaling, parameter by parameter: how far the posterior moves when the prior, or " +
            "the likelihood, is raised to a power slightly away from one. A point far along both " +
            "axes is a prior-data conflict.",
          Catalogue.sensitivityPlot(PowerScaling.analyse(byCity, varyingChains))
        ),
        figure(
          "A forecast of the held-out year",
          s"The last ${world.months - fitted} months were kept out of the fit. The bands are the " +
            "50% and 90% predictive intervals for the monthly total and the points are what " +
            "actually happened. The bands do not widen with the horizon, because this model says " +
            "the future looks like the past.",
          Catalogue.fanChart(
            future.ensemble,
            List(0.5, 0.9),
            Some(future.realised),
            "Applications per month, out of sample"
          )
        ),
        figure(
          "What partial pooling did",
          "Each city's own estimate, its estimate under the multilevel model, and the population " +
            "mean. The shrinkage is largest where a city has the least data to speak with.",
          Catalogue.shrinkagePlot(
            IArray.from(City.values.toList.map(_.toString)),
            IArray.tabulate(panel.cities)(c =>
              math.log(
                math.max(
                  (0 until panel.rows)
                    .filter(i => panel.city(i) == c)
                    .map(panel.y)
                    .sum
                    .toDouble / fitted,
                  1e-3
                )
              )
            ),
            cityRates,
            draws.map(q => Model.values(byCity, q)("mu")(0)).sum / draws.length
          )
        ),
        Figure(
          "The whole diagnostic page at once",
          "A grid: the same figures, concatenated into one specification. Nothing new — the " +
            "point is that a page of figures is itself a value.",
          VegaLite.concat(
            List(
              Catalogue.tracePlot(chainsOf("mu", 0), "mu").copy(width = 280, height = 160),
              Catalogue
                .densityPlot(chainsOf("tau", 0), "tau", reflectAt = Some(0.0))
                .copy(width = 280, height = 160)
            ),
            2
          )
        )
      )

  // --- the models ---------------------------------------------------------------------------

  /** A rate per city, with the population above it and a dispersion shared by everyone.
    *
    * Non-centred: `z` is standardised and `mu + tau * z` rebuilds the city's log rate, which is
    * what keeps the sampler out of the funnel when `tau` can approach zero.
    */
  def varying(panel: CityMonths, anchor: Double): Model[ByCity] & PointwiseLikelihood[ByCity] =
    new Model[ByCity] with PointwiseLikelihood[ByCity]:
      def params: ParamSpec[ByCity] =
        (
          ParamSpec.real("mu"),
          ParamSpec.positive("tau"),
          ParamSpec.positive("dispersion"),
          ParamSpec.vector("z", panel.cities)
        ).mapN[ByCity]([A] => (m: A, t: A, d: A, z: IArray[A]) => ByCity(m, t, d, z))

      def logPrior[A: Real](p: ByCity[A]): A =
        LogDensity.normal(p.mu, anchor.lift[A], 1.0.lift[A]) +
          LogDensity.halfNormal(p.tau, 0.5.lift[A]) +
          LogDensity.exponential(p.dispersion, 0.05.lift[A]) +
          LogDensity.normal(p.z, 0.0.lift[A], 1.0.lift[A])

      def logLikelihood[A: Real](p: ByCity[A]): A =
        val r = Real[A]
        val means = r.vectorOf(panel.cities)(c => r.exp(p.mu + p.tau * p.z(c)))
        r.sum(
          r.vectorOf(panel.rows)(i =>
            LogMass.negativeBinomial2(panel.y(i), means(panel.city(i)), p.dispersion)
          )
        )

      override def derived(p: ByCity[Double]): Map[String, IArray[Double]] =
        Map("logRate" -> IArray.tabulate(panel.cities)(c => p.mu + p.tau * p.z(c)))

      def pointwise(p: ByCity[Double]): IArray[Double] =
        val means = IArray.tabulate(panel.cities)(c => math.exp(p.mu + p.tau * p.z(c)))
        IArray.tabulate(panel.rows)(i =>
          LogMass.negativeBinomial2(panel.y(i), means(panel.city(i)), p.dispersion)
        )

  /** The same likelihood with one rate for everybody. */
  def pooled(panel: CityMonths, anchor: Double): Model[OneRate] & PointwiseLikelihood[OneRate] =
    new Model[OneRate] with PointwiseLikelihood[OneRate]:
      def params: ParamSpec[OneRate] =
        (ParamSpec.real("level"), ParamSpec.positive("dispersion"))
          .mapN[OneRate]([A] => (l: A, d: A) => OneRate(l, d))

      def logPrior[A: Real](p: OneRate[A]): A =
        LogDensity.normal(p.level, anchor.lift[A], 1.0.lift[A]) +
          LogDensity.exponential(p.dispersion, 0.05.lift[A])

      def logLikelihood[A: Real](p: OneRate[A]): A =
        val r = Real[A]
        val mean = r.exp(p.level)
        r.sum(
          r.vectorOf(panel.rows)(i => LogMass.negativeBinomial2(panel.y(i), mean, p.dispersion))
        )

      def pointwise(p: OneRate[Double]): IArray[Double] =
        val mean = math.exp(p.level)
        IArray.tabulate(panel.rows)(i => LogMass.negativeBinomial2(panel.y(i), mean, p.dispersion))

  /** The multilevel model as a generative one, which is what calibration needs. */
  def generative(panel: CityMonths, anchor: Double): GenerativeModel[ByCity, IArray[Int]] =
    GenerativeModel(
      panel.y,
      counts => varying(panel.withCounts(counts), anchor),
      new Generative[ByCity, IArray[Int]]:
        def samplePrior[F[_]: Monad: Rng]: F[ByCity[Double]] =
          for
            mu <- Rng[F].normal.map(z => anchor + z)
            tau <- Rng[F].normal.map(z => math.abs(0.5 * z))
            dispersion <- Rng[F].uniform.map(u => -math.log(u) / 0.05)
            z <- Monad[F].replicateA(panel.cities, Rng[F].normal)
          yield ByCity(mu, tau, dispersion, IArray.from(z))

        def simulateData[F[_]: Monad: Rng](p: ByCity[Double]): F[IArray[Int]] =
          val means = IArray.tabulate(panel.cities)(c => math.exp(p.mu + p.tau * p.z(c)))
          (0 until panel.rows).toList
            .traverse(i => count[F](means(panel.city(i)), p.dispersion))
            .map(IArray.from)

        def named(p: ByCity[Double]): Map[String, IArray[Double]] =
          Map(
            "mu" -> IArray(p.mu),
            "tau" -> IArray(p.tau),
            "dispersion" -> IArray(p.dispersion),
            "z" -> p.z
          )
    )

  // --- what the figures need ----------------------------------------------------------------

  /** The replicated series, their PIT values, and the forecast of the held-out months. */
  final case class Futures(
      replicated: IArray[IArray[Double]],
      pits: IArray[Double],
      ensemble: Ensemble[Unit],
      realised: IArray[Double]
  )

  private def futures(
      world: World,
      panel: CityMonths,
      model: Model[ByCity] & PointwiseLikelihood[ByCity],
      chains: List[Chain],
      settings: Settings
  ): Either[BayesError, Futures] =
    val draws = chains.flatMap(_.draws)
    val horizon = world.months - panel.months
    val thinned =
      if draws.length <= settings.replicates then draws
      else
        val every = draws.length / settings.replicates
        draws.zipWithIndex.collect { case (q, i) if i % every == 0 => q }.take(settings.replicates)
    val program = thinned.traverse { q =>
      val p = Model.constrain(model, q)
      val means = IArray.tabulate(panel.cities)(c => math.exp(p.mu + p.tau * p.z(c)))
      for
        replicate <- (0 until panel.rows).toList.traverse(i =>
          count[Rand](means(panel.city(i)), p.dispersion)
        )
        ahead <- (0 until math.max(horizon, 0)).toList.traverse(_ =>
          (0 until panel.cities).toList
            .traverse(c => count[Rand](means(c), p.dispersion))
            .map(_.sum)
        )
      yield
        val byMonth = IArray.tabulate(panel.months)(m =>
          (0 until panel.rows).filter(i => panel.month(i) == m).map(i => replicate(i)).sum.toDouble
        )
        (byMonth, IArray.from(ahead.map(_.toDouble)))
    }
    val simulated = program.runA(settings.seed.fold(4L)).value
    val replicated = IArray.from(simulated.map(_._1))
    val observed = panel.perMonth
    val pits = IArray.tabulate(panel.months) { m =>
      Calibration.pit(IArray.tabulate(replicated.length)(r => replicated(r)(m)), observed(m))
    }
    val realised = IArray.tabulate(math.max(horizon, 0))(h =>
      (0 until City.values.length).map(c => world.counts(c)(panel.months + h)).sum.toDouble
    )
    for
      periods <- Periods.monthly(
        settings.start.plusMonths(panel.months.toLong),
        math.max(horizon, 0)
      )
      ensemble <- Ensemble.of(
        simulated.length,
        periods,
        Map(() -> IArray.from(simulated.map(_._2)))
      )
    yield Futures(replicated, pits, ensemble, realised)

  /** Leave-one-out cross-validation of a fitted model, with the relative sample size per row. */
  private def crossValidate[T[_]](
      model: Model[T] & PointwiseLikelihood[T],
      chains: List[Chain]
  ): LooResult =
    val perChain =
      chains.map(c => IArray.from(c.draws.map(q => model.pointwise(Model.constrain(model, q)))))
    val total = perChain.map(_.length).sum
    val rows = perChain.headOption.flatMap(_.headOption).map(_.length).getOrElse(0)
    val logLik = IArray.tabulate(rows)(i => IArray.from(perChain.flatMap(_.map(_(i)))))
    val relative = IArray.tabulate(rows) { i =>
      val columns = IArray.from(perChain.map(draws => IArray.from(draws.map(_(i)))))
      val ess = Convergence.essBasic(columns)
      if ess.isNaN || !(ess > 0.0) then 1.0 else ess / total
    }
    Loo.psis(logLik, relative)

  /** One count from the gamma-Poisson. A rejected pair of parameters gives `-1`, which no
    * likelihood can mistake for data.
    */
  private def count[F[_]: Monad: Rng](mean: Double, dispersion: Double): F[Int] =
    NegBinomial2.of(mean, dispersion).fold(_ => Monad[F].pure(-1), _.sample[F])

  private def figure(heading: String, caption: String, plot: Plot): Figure =
    Figure(heading, caption, VegaLite.spec(plot))
