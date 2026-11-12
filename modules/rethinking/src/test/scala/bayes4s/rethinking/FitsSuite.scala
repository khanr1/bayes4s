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
import bayes4s.syntax.{*, given}
import bayes4s.testkit.Approx
import weaver.SimpleIOSuite

/** The workflow verbs, each held to the core function it is a name for.
  *
  * The facade's whole claim is that it adds no statistics. These are the tests of that claim: the
  * numbers `precis` reports are `Summary`'s, the ranking `compare` produces agrees with
  * `Loo.compare` on the same models, and `extractSamples` is the fit itself.
  *
  * A façade that drifted from the core would be worse than no façade, because a reader would trust
  * it.
  */
object FitsSuite extends SimpleIOSuite with Approx:

  private val y = IArray(4.1, 5.2, 3.8, 4.9, 5.5, 4.4, 4.7, 5.1, 4.3, 5.0)

  final case class Mean[A](mu: A, sigma: A)

  private val model: Model[Mean] & PointwiseLikelihood[Mean] =
    new Model[Mean] with PointwiseLikelihood[Mean]:
      def params: ParamSpec[Mean] =
        (ParamSpec.real("mu"), ParamSpec.positive("sigma"))
          .mapN[Mean]([A] => (mu: A, sigma: A) => Mean(mu, sigma))
      def logPrior[A: Real](p: Mean[A]): A =
        LogDensity.normal(p.mu, 0.0.lift[A], 10.0.lift[A]) +
          LogDensity.halfNormal(p.sigma, 5.0.lift[A])
      def logLikelihood[A: Real](p: Mean[A]): A =
        Real[A].normalSum(y, Real[A].filled(y.length)(p.mu), p.sigma)
      def pointwise(p: Mean[Double]): IArray[Double] =
        IArray.tabulate(y.length)(i => LogDensity.normal(y(i), p.mu, p.sigma))

  final case class Location[A](mu: A)

  /** The same data under a model that says the scale is known, so the two can be compared. */
  private val fixedScale: Model[Location] & PointwiseLikelihood[Location] =
    new Model[Location] with PointwiseLikelihood[Location]:
      def params: ParamSpec[Location] =
        ParamSpec.real("mu").map[Location]([A] => (mu: A) => Location(mu))
      def logPrior[A: Real](p: Location[A]): A =
        LogDensity.normal(p.mu, 0.0.lift[A], 10.0.lift[A])
      def logLikelihood[A: Real](p: Location[A]): A =
        Real[A].normalSum(y, Real[A].filled(y.length)(p.mu), 1.0.lift[A])
      def pointwise(p: Location[Double]): IArray[Double] =
        IArray.tabulate(y.length)(i => LogDensity.normal(y(i), p.mu, 1.0))

  private val fit = Fits.ulam(model, chains = 2, warmup = 500, samples = 500, seed = Seed(11L))
  private val fitFixed =
    Fits.ulam(fixedScale, chains = 2, warmup = 500, samples = 500, seed = Seed(12L))

  private def logLikOf[T[_]](
      m: Model[T] & PointwiseLikelihood[T],
      chains: List[Chain]
  ): IArray[IArray[Double]] =
    val rows = chains.flatMap(_.draws).map(q => m.pointwise(Model.constrain(m, q)))
    IArray.tabulate(y.length)(i => IArray.from(rows.map(_(i))))

  pureTest("precis reports exactly what Summary reports") {
    fit match
      case Left(error) => failure(error.message)
      case Right(chains) =>
        val facade = Fits.precis(model, chains)
        val core = Summary.of(model, chains)
        check(facade == core, "precis invented a number of its own")
          .and(check(facade.nonEmpty, "precis reported nothing"))
          // The text form carries the book's 89% columns and the same means.
          .and {
            val text = Fits.precisText(model, chains)
            check(
              text.contains("5.5%") && text.contains("94.5%"),
              s"the columns are: ${text.linesIterator.next()}"
            )
          }
          .and {
            val text = Fits.precisText(model, chains)
            check(text.contains("n_eff") && text.contains("Rhat"), "the diagnostics are missing")
          }
          .and {
            // The interval in the text is the core's, at the book's mass.
            val draws = IArray.from(chains.flatMap(_.draws.map(q => Model.constrain(model, q).mu)))
            val (lower, _) = Intervals.equalTailed(draws, 0.89)
            val row = Fits.precisText(model, chains).linesIterator.toList.find(_.startsWith("mu"))
            // Formatted under `Locale.ROOT`, as the table is: an interpolator would follow the
            // machine's locale and look for `4,41` in a table that says `4.41`.
            val shown = String.format(java.util.Locale.ROOT, "%.2f", lower)
            check(row.exists(_.contains(shown)), s"the interval is not the core's: $row")
          }
  }

  pureTest("compare agrees with Loo.compare about which model is better, and by how much") {
    (fit, fitFixed) match
      case (Right(a), Right(b)) =>
        val waicA = Rethinking.WAIC(logLikOf(model, a))
        val waicB = Rethinking.WAIC(logLikOf(fixedScale, b))
        val ranked = Fits.compare(List("free scale" -> waicA, "fixed scale" -> waicB))

        val looA = Loo.psis(logLikOf(model, a), 1.0)
        val looB = Loo.psis(logLikOf(fixedScale, b), 1.0)
        val core = Loo.compare(List("free scale" -> looA, "fixed scale" -> looB))

        check(ranked.length == 2, s"${ranked.length} rows")
          // Deviance is `-2 elpd`, so the order has to be the reverse of elpd's, and the best
          // model has to be the same one. A sign error here is the mistake this test exists for.
          .and(
            check(
              ranked.head.name == core.head.name,
              s"${ranked.head.name} against ${core.head.name}"
            )
          )
          .and(check(ranked.head.difference == 0.0, "the best model's difference is not zero"))
          // The two criteria are not the same number, but on a model this simple they agree on
          // the *gap* to well within its own standard error.
          .and {
            val waicGap = ranked(1).difference
            val looGap = 2.0 * math.abs(core(1).difference)
            check(
              math.abs(waicGap - looGap) < 2.0 * ranked(1).differenceStandardError + 1e-9,
              s"WAIC says $waicGap and LOO says $looGap, against an error of ${ranked(1).differenceStandardError}"
            )
          }
          // Akaike weights are a distribution over the models.
          .and {
            val total = ranked.map(_.weight).sum
            close(total, 1.0, 1e-12, s"the weights sum to $total")
          }
          .and(
            check(ranked.forall(r => r.weight >= 0.0 && r.weight <= 1.0), "a weight is not a share")
          )
      case other => failure(s"a fit failed: $other")
  }

  pureTest("PSIS is the deviance of the core's cross-validation, and nothing else") {
    fit match
      case Left(error) => failure(error.message)
      case Right(chains) =>
        val logLik = logLikOf(model, chains)
        val (deviance, result) = Rethinking.PSIS(logLik)
        val core = Loo.psis(logLik, 1.0)
        // `LooResult` holds `IArray`s, whose equality is by reference, so the comparison is of the
        // numbers rather than of the objects.
        check(result.elpd == core.elpd && result.se == core.se, "PSIS changed the estimate")
          .and(
            check(
              result.paretoK.toList == core.paretoK.toList,
              "PSIS changed the shape diagnostics"
            )
          )
          .and(close(deviance, -2.0 * core.elpd, 1e-12, s"the deviance is $deviance"))
  }

  pureTest("extractSamples is the fit, and extractPrior is the prior") {
    (fit, Fits.extractSamples(model, fit.getOrElse(Nil))) match
      case (Right(chains), Right(draws)) =>
        val prior =
          Fits.extractPrior(model, chains = 1, warmup = 300, samples = 300, seed = Seed(5L))
        check(draws.chainCount == 2, s"${draws.chainCount} chains")
          .and(check(draws.iterations == 500, s"${draws.iterations} iterations"))
          .and(check(draws.names.toList.contains("mu"), s"the names are ${draws.names.toList}"))
          .and(check(prior.isRight, s"the prior did not sample: $prior"))
          .and {
            // The prior is far wider than the posterior, which is the whole point of looking at
            // it: ten observations have pinned `mu` down and the prior has not.
            val posterior =
              IArray.from(chains.flatMap(_.draws.map(q => Model.constrain(model, q).mu)))
            val fromPrior = prior
              .map(cs => IArray.from(cs.flatMap(_.draws.map(q => Model.constrain(model, q).mu))))
            fromPrior match
              case Left(error) => failure(error.message)
              case Right(values) =>
                def spread(xs: IArray[Double]) =
                  val mean = xs.sum / xs.length
                  math.sqrt(xs.map(v => (v - mean) * (v - mean)).sum / (xs.length - 1))
                check(
                  spread(values) > 3.0 * spread(posterior),
                  s"${spread(values)} against ${spread(posterior)}"
                )
          }
      case other => failure(s"a fit failed: $other")
  }

  pureTest("coeftab puts one model per column and one parameter per row") {
    (fit, fitFixed) match
      case (Right(a), Right(b)) =>
        val table =
          Fits.coeftab(List(("free", Summary.of(model, a)), ("fixed", Summary.of(fixedScale, b))))
        check(table.contains("mu"), s"the rows are ${table.keys.toList.sorted}")
          .and(check(table("mu").keySet == Set("free", "fixed"), "a model is missing from a row"))
          // `sigma` is a parameter of one model and a constant in the other, so its row has one
          // column: that is the shape `coeftab` exists to show.
          .and(
            check(
              table.get("sigma").exists(_.keySet == Set("free")),
              s"sigma: ${table.get("sigma")}"
            )
          )
      case other => failure(s"a fit failed: $other")
  }

  pureTest("the figures are the catalogue's, under the book's names") {
    val chains = IArray.tabulate(2)(c => IArray.tabulate(200)(i => math.sin(i * 0.07 + c)))
    val rows = fit.map(cs => Summary.of(model, cs)).getOrElse(Nil)
    // A `Plot` holds `IArray`s, whose equality is by reference, so the comparison is of the
    // compiled specifications — which are `Json` and compare by value.
    check(
      VegaLite.spec(Figures.traceplot(chains, "mu")) == VegaLite.spec(
        Catalogue.tracePlot(chains, "mu")
      ),
      "traceplot differs"
    ).and(
      check(
        VegaLite.spec(Figures.trankplot(chains, "mu")) == VegaLite.spec(
          Catalogue.trankPlot(chains, "mu")
        ),
        "trankplot differs"
      )
    ).and(
      check(
        VegaLite.spec(Figures.dens(chains, "mu")) ==
          VegaLite.spec(Catalogue.densityPlot(chains, "mu", Bandwidth.Silverman, 0.89)),
        "dens differs"
      )
    )
      // Every one of them compiles to a specification, which is what makes it a figure at all.
      .and(check(rows.nonEmpty, "no summary to plot"))
      .and {
        val specs = List(
          VegaLite.spec(Figures.dens(chains, "mu")),
          VegaLite.spec(Figures.shade(chains, "mu")),
          VegaLite.spec(Figures.traceplot(chains, "mu")),
          VegaLite.spec(Figures.trankplot(chains, "mu")),
          VegaLite.spec(Figures.precisPlot(rows)),
          VegaLite
            .spec(Figures.postcheck(IArray(1.0, 2.0), IArray(IArray(1.1, 2.1), IArray(0.9, 1.9))))
        )
        check(specs.forall(_.noSpaces.contains("\"values\":")), "a figure carries no data")
      }
  }
