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

/** The finite catalogue of statistical plots.
  *
  * Not a grammar of graphics: a list of the pictures a Bayesian workflow actually needs, each
  * built from the types the rest of the library already produces, each a pure value. When one of
  * them is nearly right, `VegaLite.raw` hands over the specification rather than growing an
  * option here — a catalogue that tries to cover everything becomes a second grammar, badly.
  *
  * The vocabulary is the manual's (§9.6): samples from the posterior, compatibility intervals,
  * divergent transitions.
  * **Example.** Each figure is built from the types the rest of the library already produces, and
  * each is a pure value:
  *
  * ```scala
  * //{
  * import java.time.YearMonth
  * //}
  * val chains: IArray[IArray[Double]] =
  *   IArray.tabulate(2)(c => IArray.tabulate(400)(i => math.sin(i * 0.07 + c)))
  *
  * // The first thing to look at: do the chains overlap, and where were the divergent transitions?
  * val trace: Plot = Catalogue.tracePlot(chains, "mu")
  * val marked: Plot =
  *   Catalogue.tracePlot(chains, "mu", Some(IArray.fill(2)(IArray.fill(400)(false))))
  *
  * // A density per chain, reflected at zero so the mass of a positive parameter does not leak past
  * // the boundary.
  * val density: Plot = Catalogue.densityPlot(chains, "tau", reflectAt = Some(0.0))
  *
  * // A forecast, with the bands widest first so the narrow one stays visible.
  * val periods = Periods.monthly(YearMonth.of(2027, 1), 6).getOrElse(Periods.empty)
  * val ensemble: Either[BayesError, Ensemble[Unit]] =
  *   Ensemble.of(4, periods, Map(() -> IArray.tabulate(4)(r => IArray.tabulate(6)(p => 100.0 + p + r))))
  * val fan: Either[BayesError, Plot] = ensemble.map(e => Catalogue.fanChart(e, List(0.5, 0.9)))
  * ```
  */
object Catalogue:

  // --- diagnostics ------------------------------------------------------------------------------

  /** Chains over iterations, with divergent transitions marked.
    *
    * The first plot to look at and the one that needs no statistics: if the chains do not overlap,
    * nothing else in the fit is worth reading.
    */
  def tracePlot(
      chains: IArray[IArray[Double]],
      parameter: String,
      divergent: Option[IArray[IArray[Boolean]]] = None
  ): Plot =
    val lines = (0 until chains.length).toList.map { c =>
      val n = chains(c).length
      Layer.Line(
        IArray.tabulate(n)(_.toDouble),
        chains(c),
        Some(IArray.fill(n)(s"chain ${c + 1}"))
      )
    }
    val marks = divergent.toList.flatMap { flags =>
      (0 until chains.length).toList.flatMap { c =>
        val points = (0 until chains(c).length).filter(i => flags(c)(i))
        if points.isEmpty then Nil
        else
          List(
            Layer.Points(
              IArray.from(points.map(_.toDouble)),
              IArray.from(points.map(i => chains(c)(i))),
              Some(IArray.fill(points.length)(true))
            )
          )
      }
    }
    Plot(lines ++ marks, Some(s"Trace of $parameter"), Some("iteration"), Some(parameter))

  /** Rank histograms per chain: the trace plot's honest replacement.
    *
    * A trace plot of a long chain is a smear. Ranks pooled over chains and binned per chain are
    * flat when the chains are interchangeable and visibly tilted when they are not, however long
    * they are.
    */
  def trankPlot(chains: IArray[IArray[Double]], parameter: String, bins: Int = 20): Plot =
    val counts = Ranks.histogram(chains, bins)
    val layers = (0 until counts.length).toList.map { c =>
      Layer.Bars(
        IArray.tabulate(bins + 1)(i => i.toDouble / bins),
        IArray.tabulate(bins)(i => counts(c)(i).toDouble),
        Some(IArray.fill(bins)(s"chain ${c + 1}"))
      )
    }
    Plot(
      layers,
      Some(s"Rank histogram of $parameter"),
      Some("rank, scaled to (0, 1)"),
      Some("count")
    )

  /** Autocorrelation by lag, per chain: how much of each sample is new information. */
  def autocorrelationPlot(chains: IArray[IArray[Double]], parameter: String, lags: Int = 30): Plot =
    val layers = (0 until chains.length).toList.map { c =>
      val covariance = Convergence.autocovariance(chains(c))
      val scale =
        if covariance.length == 0 || covariance(0) == 0.0 then Double.NaN else covariance(0)
      val upTo = math.min(lags, covariance.length - 1)
      Layer.Line(
        IArray.tabulate(upTo + 1)(_.toDouble),
        IArray.tabulate(upTo + 1)(l => covariance(l) / scale),
        Some(IArray.fill(upTo + 1)(s"chain ${c + 1}"))
      )
    }
    Plot(
      layers :+ Layer.Rule(0.0, Axis.Y),
      Some(s"Autocorrelation of $parameter"),
      Some("lag"),
      Some("correlation")
    )

  /** The energy of each transition against the change in energy, with E-BFMI written on it. */
  def energyPlot(energies: IArray[Double], eBfmi: Double): Plot =
    val changes =
      IArray.tabulate(math.max(energies.length - 1, 0))(i => energies(i + 1) - energies(i))
    val marginal = IArray.tabulate(changes.length)(i => energies(i + 1))
    Plot(
      List(
        Layer.Points(marginal, changes),
        Layer.Text(
          if marginal.length == 0 then 0.0 else marginal.min,
          if changes.length == 0 then 0.0 else changes.max,
          f"E-BFMI $eBfmi%.2f"
        )
      ),
      Some("Energy"),
      Some("energy"),
      Some("change in energy")
    )

  /** Two parameters against each other, with divergent transitions in a second colour.
    *
    * Where a funnel shows itself: the divergences cluster in the neck, and no summary says that.
    */
  def pairsPlot(
      first: IArray[Double],
      second: IArray[Double],
      names: (String, String),
      divergent: Option[IArray[Boolean]] = None
  ): Plot =
    Plot(
      List(Layer.Points(first, second, divergent)),
      Some(s"${names._1} against ${names._2}"),
      Some(names._1),
      Some(names._2)
    )

  // --- the posterior ----------------------------------------------------------------------------

  /** A density per chain, with a compatibility interval shaded under the pooled one. */
  def densityPlot(
      chains: IArray[IArray[Double]],
      parameter: String,
      rule: Bandwidth = Bandwidth.Silverman,
      mass: Double = 0.9,
      points: Int = 128,
      reflectAt: Option[Double] = None
  ): Plot =
    val pooled = IArray.from(chains.toList.flatMap(_.toList))
    val bandwidth = Density.bandwidth(pooled, rule)
    val grid = Density.grid(pooled, points, bandwidth)
    val perChain = (0 until chains.length).toList.map { c =>
      Layer.Line(
        grid,
        Density.gaussian(chains(c), bandwidth, grid, reflectAt),
        Some(IArray.fill(grid.length)(s"chain ${c + 1}"))
      )
    }
    val tail = (1.0 - mass) / 2.0
    val low = Quantile.quantile(pooled, tail)
    val high = Quantile.quantile(pooled, 1.0 - tail)
    val inside = IArray.from(grid.toList.filter(g => g >= low && g <= high))
    val shaded =
      if inside.length == 0 then Nil
      else
        List(
          Layer.Ribbon(
            inside,
            IArray.fill(inside.length)(0.0),
            Density.gaussian(pooled, bandwidth, inside, reflectAt)
          )
        )
    Plot(
      shaded ++ perChain,
      Some(s"Posterior of $parameter"),
      Some(parameter),
      Some("density")
    )

  /** Means and compatibility intervals per parameter: the picture of a summary table. */
  def intervalPlot(
      rows: List[(String, Double, Double, Double)],
      title: String = "Parameters"
  ): Plot =
    Plot(
      List(
        Layer.Interval(
          IArray.from(rows.map(_._1)),
          IArray.from(rows.map(_._2)),
          IArray.from(rows.map(_._3)),
          IArray.from(rows.map(_._4))
        )
      ),
      Some(title),
      Some("value"),
      Some("parameter")
    )

  /** Models ranked by expected predictive fit, with the error of each difference. */
  def compareModelsPlot(comparisons: List[LooComparison]): Plot =
    Plot(
      List(
        Layer.Interval(
          IArray.from(comparisons.map(_.name)),
          IArray.from(comparisons.map(_.elpd)),
          IArray.from(comparisons.map(c => c.elpd - 2.0 * c.se)),
          IArray.from(comparisons.map(c => c.elpd + 2.0 * c.se))
        )
      ),
      Some("Expected log predictive density"),
      Some("elpd"),
      Some("model")
    )

  // --- checking ---------------------------------------------------------------------------------

  /** Replicated series against the observed one, as a band per period. */
  def predictivePlot(
      observed: IArray[Double],
      replicated: IArray[IArray[Double]],
      mass: Double = 0.9,
      title: String = "Posterior predictive"
  ): Plot =
    val periods = observed.length
    val x = IArray.tabulate(periods)(_.toDouble)
    val tail = (1.0 - mass) / 2.0
    val lower = IArray.tabulate(periods) { p =>
      Quantile.quantile(IArray.tabulate(replicated.length)(r => replicated(r)(p)), tail)
    }
    val upper = IArray.tabulate(periods) { p =>
      Quantile.quantile(IArray.tabulate(replicated.length)(r => replicated(r)(p)), 1.0 - tail)
    }
    Plot(
      List(Layer.Ribbon(x, lower, upper), Layer.Points(x, observed)),
      Some(title),
      Some("period"),
      Some("count")
    )

  /** Calibration ranks: the histogram, and the difference from a flat one with its band. */
  def sbcPlot(
      ranks: IArray[Int],
      possible: Int,
      bins: Int = 20,
      mass: Double = 0.95,
      seed: Seed = Seed(1L)
  ): Plot =
    val n = ranks.length
    val scaled = IArray.tabulate(n)(i => (ranks(i) + 0.5) / (possible + 1.0))
    val counts = (0 until n).foldLeft(Vector.fill(bins)(0.0)) { (collected, i) =>
      val bin = math.min(math.max((scaled(i) * bins).toInt, 0), bins - 1)
      collected.updated(bin, collected(bin) + 1.0)
    }
    val points = IArray.tabulate(bins - 1)(i => (i + 1.0) / bins)
    val band = Ecdf.simultaneousBand(n, points, points, mass, seed, 500)
    val observed = Ecdf.values(scaled, points)
    Plot(
      List(
        Layer.Bars(IArray.tabulate(bins + 1)(i => i.toDouble / bins), IArray.from(counts)),
        Layer.Ribbon(
          points,
          IArray.tabulate(bins - 1)(i => band.lower(i) * n),
          IArray.tabulate(bins - 1)(i => band.upper(i) * n)
        ),
        Layer.Line(points, IArray.tabulate(bins - 1)(i => observed(i) * n))
      ),
      Some("Simulation-based calibration"),
      Some("rank, scaled to (0, 1)"),
      Some("count")
    )

  /** The shape parameter per observation, with the thresholds drawn. */
  def paretoKPlot(result: LooResult, threshold: Double = 0.7): Plot =
    val n = result.paretoK.length
    Plot(
      List(
        Layer.Points(
          IArray.tabulate(n)(_.toDouble),
          result.paretoK,
          Some(IArray.tabulate(n)(i => result.paretoK(i) > threshold))
        ),
        Layer.Rule(threshold, Axis.Y),
        Layer.Rule(0.5, Axis.Y)
      ),
      Some("Pareto shape by observation"),
      Some("observation"),
      Some("k")
    )

  /** The probability integral transform's cumulative function, with a simultaneous band. */
  def pitPlot(
      pits: IArray[Double],
      mass: Double = 0.95,
      points: Int = 20,
      seed: Seed = Seed(1L)
  ): Plot =
    val n = pits.length
    val at = IArray.tabulate(points)(i => (i + 1.0) / (points + 1.0))
    val band = Ecdf.simultaneousBand(n, at, at, mass, seed, 500)
    Plot(
      List(
        Layer.Ribbon(at, band.lower, band.upper),
        Layer.Line(at, Ecdf.values(pits, at)),
        Layer.Line(at, at)
      ),
      Some("Calibration of the transform"),
      Some("transform"),
      Some("cumulative share")
    )

  /** Prior sensitivity against likelihood sensitivity, per parameter, with the threshold. */
  def sensitivityPlot(sensitivities: List[Sensitivity], threshold: Double = 0.05): Plot =
    Plot(
      List(
        Layer.Points(
          IArray.from(sensitivities.map(_.prior)),
          IArray.from(sensitivities.map(_.likelihood)),
          Some(IArray.from(sensitivities.map(s => s.prior > threshold && s.likelihood > threshold)))
        ),
        Layer.Rule(threshold, Axis.X),
        Layer.Rule(threshold, Axis.Y)
      ),
      Some("Power-scaling sensitivity"),
      Some("sensitivity to the prior"),
      Some("sensitivity to the likelihood")
    )

  // --- forecasting ------------------------------------------------------------------------------

  /** Quantile bands by period, with what actually happened drawn over them. */
  def fanChart(
      ensemble: Ensemble[Unit],
      masses: List[Double] = List(0.5, 0.9),
      observed: Option[IArray[Double]] = None,
      title: String = "Forecast"
  ): Plot =
    val periods = ensemble.periods.count
    val x = IArray.tabulate(periods)(_.toDouble)
    val bands = masses.sortBy(-_).flatMap { mass =>
      val tail = (1.0 - mass) / 2.0
      val quantiles = ensemble.quantiles(List(tail, 1.0 - tail))
      quantiles.get(()).toList.map(rows => Layer.Ribbon(x, rows(0), rows(1)))
    }
    val median = ensemble.quantiles(List(0.5)).get(()).toList.map(rows => Layer.Line(x, rows(0)))
    val realised = observed.toList.map(o => Layer.Points(x, o))
    Plot(bands ++ median ++ realised, Some(title), Some("period"), Some("total"))

  // --- multilevel -------------------------------------------------------------------------------

  /** What partial pooling did to each group: from its own estimate towards the population's. */
  def shrinkagePlot(
      labels: IArray[String],
      unpooled: IArray[Double],
      pooled: IArray[Double],
      population: Double
  ): Plot =
    val y = IArray.tabulate(labels.length)(_.toDouble)
    Plot(
      List(
        Layer.Points(unpooled, y),
        Layer.Points(pooled, y, Some(IArray.fill(labels.length)(true))),
        Layer.Rule(population, Axis.X)
      ),
      Some("Shrinkage towards the population"),
      Some("estimate"),
      Some("group")
    )
