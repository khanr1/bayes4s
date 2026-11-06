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
import cats.syntax.all.*
import weaver.SimpleIOSuite

/** What cross-validation is for: deciding whether a group gets its own intercept, the same one as
  * everybody, or something in between.
  *
  * Three models on the same invented data — complete pooling, no pooling, partial pooling — and
  * leave-one-out has to prefer the third. Two datasets are needed to say that with any force, and
  * the reason is structural rather than a matter of sample size: the hierarchical fit interpolates
  * between the two extremes, so it is always close to whichever one the data supports and the
  * difference on **that** side is inside its own standard error. It is the other side that
  * cross-validation rejects. So:
  *
  *   - with a between-group spread twice the within-group one, complete pooling is rejected;
  *   - with a spread below the standard error of a group's own mean, no pooling is rejected;
  *   - partial pooling is the only model rejected on neither, which is the argument for it.
  *
  * A run across five seeds for each dataset put the rejected model at 4.4 to 9.9 standard errors
  * on the first and 1.8 to 3.1 on the second, and never put partial pooling more than 0.8 behind
  * the leader; the thresholds below are stated with that margin in mind. The one configuration
  * where partial pooling is genuinely rejected — a spread of half the within-group one over thirty
  * groups of three, where complete pooling won by 2.3 standard errors — is recorded in
  * `docs/validation.md`: the claim is about these data, not about hierarchies in general.
  *
  * The obligatory test of §5.7 comes first, though. The sum of a model's pointwise log likelihood
  * is its `logLikelihood`; without that, every number here would be about a model nobody fitted.
  */
object PoolingSuite extends SimpleIOSuite with Approx:

  /** Groups simulated from a level above them, which is what makes the comparison meaningful. */
  private def simulate(
      groups: Int,
      perGroup: Int,
      between: Double,
      within: Double,
      seed: Long
  ): MultilevelModels.Grouped =
    val rows = groups * perGroup
    val program = for
      effects <- Monad[Rand].replicateA(groups, Rng[Rand].normal.map(z => 2.0 + between * z))
      drawn <- (0 until rows).toList.traverse { i =>
        Rng[Rand].normal.map(e => effects(i % groups) + within * e)
      }
    yield drawn
    MultilevelModels.Grouped(
      IArray.tabulate(rows)(i => i % groups),
      IArray.tabulate(rows)(i => math.sin(i * 0.5)),
      IArray.from(program.runA(Seed(seed)).value),
      groups
    )

  private val chains = 2
  private val warmup = 600
  private val samples = 1000

  private def fit[T[_]](model: Model[T], seed: Long): Either[BayesError, List[Chain]] =
    val target = Model.target(model)
    (0 until chains).toList.traverse { c =>
      val program = for
        start <- Monad[Rand]
          .replicateA(model.params.unconstrainedSize, Rng[Rand].uniform)
          .map(us => IArray.from(us.map(u => 2.0 * (2.0 * u - 1.0))))
        chain <- Mcmc.run[Rand](
          Nuts.kernel[Rand](target, NutsSettings()),
          target,
          start,
          warmup,
          samples,
          AdaptationSettings()
        )
      yield chain
      program.runA(Seed(seed).fold(c.toLong)).value
    }

  /** Fit, then turn the posterior into the log likelihood per observation per draw that PSIS-LOO
    * reads. The relative effective sample size is estimated from the chains themselves, per
    * observation, which is what `Loo.psis` asks its caller for and what ArviZ computes internally.
    */
  private def crossValidate[T[_]](
      model: Model[T] & PointwiseLikelihood[T],
      rows: Int,
      seed: Long
  ): Either[BayesError, LooResult] =
    fit(model, seed).map { fitted =>
      val perChain = fitted.map { chain =>
        IArray.from(chain.draws.map(q => model.pointwise(Model.constrain(model, q))))
      }
      val total = perChain.map(_.length).sum
      val logLik = IArray.tabulate(rows) { i =>
        IArray.from(perChain.flatMap(_.map(_(i))))
      }
      val relative = IArray.tabulate(rows) { i =>
        val columns = IArray.from(perChain.map(draws => IArray.from(draws.map(_(i)))))
        val ess = Convergence.essBasic(columns)
        if ess.isNaN || !(ess > 0.0) then 1.0 else ess / total
      }
      Loo.psis(logLik, relative)
    }

  private val fitSeed = 4711L

  pureTest("the pointwise log likelihood sums to the log likelihood, model by model") {
    val data = simulate(8, 6, 1.2, 0.8, 20261105L)
    val binary = MultilevelModels.Binary(
      data.group,
      data.x,
      IArray.tabulate(data.rows)(i => if data.y(i) > 2.0 then 1 else 0),
      data.groups
    )
    def agrees[T[_]](name: String, model: Model[T] & PointwiseLikelihood[T]) =
      // An arbitrary point in the unconstrained space: the identity is one of arithmetic, so it
      // holds away from the posterior as well as inside it, and a point of its own per model is
      // what the differing dimensions demand.
      val at = IArray.tabulate(model.params.unconstrainedSize)(i => 0.2 * math.sin(i.toDouble))
      val p = Model.constrain(model, at)
      val parts = model.pointwise(p)
      val sum = (0 until parts.length).foldLeft(0.0)((acc, i) => acc + parts(i))
      check(parts.length == data.rows, s"$name: ${parts.length} parts for ${data.rows} rows")
        .and(closeRel(sum, model.logLikelihood(p), 1e-12, s"$name: the sum of the parts"))
    agrees("varying intercepts, centred", MultilevelModels.varyingInterceptsCentred(data))
      .and(
        agrees(
          "varying intercepts, non-centred",
          MultilevelModels.varyingInterceptsNonCentred(data)
        )
      )
      .and(agrees("complete pooling", MultilevelModels.completelyPooled(data)))
      .and(agrees("no pooling", MultilevelModels.notPooled(data)))
      .and(agrees("correlated effects", MultilevelModels.correlatedEffects(data)))
      .and(agrees("multilevel logistic", MultilevelModels.multilevelLogistic(binary)))
  }

  /** The two datasets, each rejecting one extreme, and the three models ranked on both. */
  private def ranks(
      data: MultilevelModels.Grouped
  ): Either[BayesError, List[LooComparison]] =
    for
      partial <- crossValidate(
        MultilevelModels.varyingInterceptsNonCentred(data),
        data.rows,
        fitSeed
      )
      pooled <- crossValidate(MultilevelModels.completelyPooled(data), data.rows, fitSeed)
      separate <- crossValidate(MultilevelModels.notPooled(data), data.rows, fitSeed)
    yield Loo.compare(
      List(("partial", partial), ("pooled", pooled), ("separate", separate))
    )

  private def shown(ranked: List[LooComparison]): String =
    ranked
      .map(r => f"${r.name} elpd=${r.elpd}%.2f diff=${r.difference}%.2f se=${r.se}%.2f")
      .mkString(" | ")

  pureTest("leave-one-out rejects complete pooling when the groups really differ") {
    // A between-group spread of 2 against a within-group one of 1: the groups are genuinely apart,
    // and a single intercept has to widen its own scale to cover them.
    ranks(simulate(20, 3, 2.0, 1.0, 20261106L)) match
      case Left(error) => failure(error.message)
      case Right(ranked) =>
        val pooled = ranked.find(_.name == "pooled")
        check(ranked.head.name == "partial", s"the ranking is ${shown(ranked)}")
          .and(
            check(ranked.forall(_.difference <= 0.0), s"a model beat the leader: ${shown(ranked)}")
          )
          .and(
            pooled.fold(failure("complete pooling is missing from the ranking")) { p =>
              check(
                p.difference < -3.0 * p.se,
                s"complete pooling is not rejected: ${shown(ranked)}"
              )
            }
          )
  }

  pureTest("leave-one-out rejects no pooling when a group has little of its own to say") {
    // Forty groups of two, with a spread of 0.6 against a within-group 1.0: the standard error of
    // a group's own mean is 0.71, larger than the spread, so an intercept per group is mostly
    // fitting noise — and leaving one of the two out leaves that group a single observation.
    ranks(simulate(40, 2, 0.6, 1.0, 20261110L)) match
      case Left(error) => failure(error.message)
      case Right(ranked) =>
        val separate = ranked.find(_.name == "separate")
        check(ranked.head.name == "partial", s"the ranking is ${shown(ranked)}")
          .and(
            check(ranked.forall(_.difference <= 0.0), s"a model beat the leader: ${shown(ranked)}")
          )
          .and(
            separate.fold(failure("no pooling is missing from the ranking")) { s =>
              check(
                s.difference < -1.5 * s.se,
                s"no pooling is not rejected: ${shown(ranked)}"
              )
            }
          )
  }

  /** Power scaling on a model rather than on an array of draws: the same data read under three
    * priors, and the diagnostic has to tell the three apart.
    */
  pureTest("power scaling reads the prior against the data, on a fitted model") {
    val data = simulate(20, 3, 0.6, 1.0, 20261106L)
    val mean = (0 until data.rows).foldLeft(0.0)((acc, i) => acc + data.y(i)) / data.rows
    def muOf(priorMean: Double, priorScale: Double): Either[BayesError, Sensitivity] =
      val model = MultilevelModels.completelyPooled(data, priorMean, priorScale)
      fit(model, 815L).flatMap { fitted =>
        PowerScaling
          .analyse(model, fitted)
          .find(_.name == "mu")
          .toRight(BayesError.UnknownParameter("mu"))
      }
    val readings = for
      wide <- muOf(0.0, 5.0)
      conflict <- muOf(10.0, 0.2)
      agreeing <- muOf(2.0, 0.2)
    yield (wide, conflict, agreeing)
    readings match
      case Left(error) => failure(error.message)
      case Right((wide, conflict, agreeing)) =>
        check(mean > 1.5 && mean < 3.0, f"the data averages $mean%.3f, not about 2")
          // Sixty observations against a prior of scale 5: the data decides, and scaling the prior
          // barely moves the posterior.
          .and(
            check(
              wide.diagnosis == "the data is doing the work",
              f"a wide prior reads as '${wide.diagnosis}' (prior ${wide.prior}%.4f, " +
                f"likelihood ${wide.likelihood}%.4f)"
            )
          )
          // The same data against a prior of 10 +/- 0.2: the posterior is a compromise neither
          // side would have chosen, which is what the conflict reading means.
          .and(
            check(
              conflict.diagnosis == "prior-data conflict",
              f"a prior at 10 reads as '${conflict.diagnosis}' (prior ${conflict.prior}%.4f)"
            )
          )
          .and(
            check(
              conflict.prior > 20.0 * wide.prior,
              f"moving the prior onto a conflict took its sensitivity from ${wide.prior}%.4f " +
                f"only to ${conflict.prior}%.4f"
            )
          )
          // And the diagnostic's own limit, worth asserting so that nobody reads more into the
          // label than it says: a narrow prior is sensitive whether or not it conflicts, so one
          // placed where the data already is reads as a conflict too. What separates the two is
          // the size of the reading, not the label.
          .and(
            check(
              agreeing.prior > 0.05 && conflict.prior > 5.0 * agreeing.prior,
              f"a narrow prior agreeing with the data reads ${agreeing.prior}%.4f against the " +
                f"conflicting one's ${conflict.prior}%.4f"
            )
          )
  }
