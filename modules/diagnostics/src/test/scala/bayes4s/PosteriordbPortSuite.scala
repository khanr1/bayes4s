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
import weaver.SimpleIOSuite

/** The nine ports, before they are fitted: do they load, do they evaluate, are they
  * differentiable, and do they report the quantities the references name?
  *
  * The comparison against the reference summaries is in `src/slow/scala`, because it is nine fits
  * of four chains. These checks are cheap and they are the ones that fail first when a port is
  * wrong: a data file read with the wrong field name, a density that is `NaN` at the origin, a
  * matrix the tape cannot differentiate through, a parameter the reference calls something else.
  */
object PosteriordbPortSuite extends SimpleIOSuite with Approx:

  private val ported = PosteriordbModels.all

  pureTest("all nine posteriors load") {
    val failures = ported.collect { case (name, Left(message)) => s"$name: $message" }
    check(failures.isEmpty, s"some posteriors did not load:\n${failures.mkString("\n")}")
      .and(check(ported.length == 9, s"${ported.length} posteriors, expected 9"))
  }

  pureTest("each target is a number at a central point, and at a uniform start") {
    forEach(ported) { (name, built) =>
      built match
        case Left(message) => failure(s"$name: $message")
        case Right(model) =>
          val origin = IArray.fill(model.dimension)(0.0)
          val offset = IArray.tabulate(model.dimension)(i => 0.5 * math.sin(i.toDouble))
          val atOrigin = Reverse.value(model.target, origin)
          val atOffset = Reverse.value(model.target, offset)
          check(atOrigin.isFinite, s"$name: the target at the origin is $atOrigin")
            .and(check(atOffset.isFinite, s"$name: the target at an offset point is $atOffset"))
            .and(check(model.dimension > 0, s"$name: no parameters"))
    }
  }

  /** The gradient against the dual-number oracle: this is what says the tape can differentiate
    * every one of them, the Cholesky of the Gaussian process included.
    */
  pureTest("each target's gradient agrees with the oracle to 1e-12") {
    forEach(ported) { (name, built) =>
      built match
        case Left(message) => failure(s"$name: $message")
        case Right(model) =>
          val at = IArray.tabulate(model.dimension)(i => 0.3 * math.cos(i * 0.7))
          val taped = Reverse.valueAndGradient(model.target, at)
          val oracle = Dual.gradient(model.target, at)
          closeRel(taped.value, Dual.value(model.target, at), 1e-13, s"$name: the value")
            .and(forEach((0 until model.dimension).toList) { i =>
              closeRel(taped.gradient(i), oracle(i), 1e-12, s"$name: d/du($i)")
            })
    }
  }

  pureTest("each target's gradient agrees with central differences") {
    forEach(ported) { (name, built) =>
      built match
        case Left(message) => failure(s"$name: $message")
        case Right(model) =>
          val at = IArray.tabulate(model.dimension)(i => 0.2 * math.sin(i * 1.3))
          val result = GradientCheck(model.target, at, 1e-6)
          check(result.maxError <= 1e-5, s"$name: central differences differ by ${result.maxError}")
    }
  }

  /** Every name the reference summary reports has to be a name this port produces — as a
    * parameter or as a derived quantity — or the comparison would silently skip it.
    */
  pureTest("every parameter the references name is produced by its port") {
    forEach(ported) { (name, built) =>
      built match
        case Left(message) => failure(s"$name: $message")
        case Right(model) =>
          bayes4s.testkit.Reference
            .text(s"posteriordb/$name/reference_summary.json")
            .left
            .map(_.message)
            .flatMap(PosteriordbSummary.parse(_).left.map(_.message)) match
            case Left(message) => failure(s"$name: $message")
            case Right(summary) =>
              val ours = model
                .valuesAt(IArray.fill(model.dimension)(0.1))
                .toList
                .flatMap((block, values) =>
                  if values.length == 1 then List(block)
                  else values.indices.map(i => s"$block.${i + 1}").toList
                )
                .toSet
              val wanted = summary.rows.map(_.cmdStanName).toSet
              val missing = wanted.diff(ours)
              check(
                missing.isEmpty,
                s"$name: the reference names ${missing.toList.sorted.mkString(", ")}, " +
                  s"which this port does not produce; it produces ${ours.toList.sorted.mkString(", ")}"
              ).and(check(wanted.nonEmpty, s"$name: the reference names nothing"))
    }
  }

  /** One structural check per awkward port, so that a translation that drifts is caught here
    * rather than after four chains.
    */
  pureTest("the two translations that are not line by line do what they claim") {
    val garch = PosteriordbModels.garch11
    val gp = PosteriordbModels.gaussianProcess
    val garchCheck = garch match
      case Left(message) => failure(message)
      case Right(model) =>
        // `beta1 = (1 - alpha1) beta1Raw` must stay inside its dependent bound for any point, and
        // the Jacobian of that bound must be in the target: `logPrior` is exactly `log(1 - alpha1)`.
        val at = IArray(0.3, -0.4, 0.8, 1.2)
        val p = Model.constrain(model, at)
        val derived = model.derived(p)("beta1")(0)
        check(
          derived > 0.0 && derived < 1.0 - p.alpha1,
          s"beta1 = $derived is outside (0, ${1.0 - p.alpha1})"
        ).and(
          closeRel(
            model.logPrior(p),
            math.log(1.0 - p.alpha1),
            1e-14,
            "the Jacobian of the dependent bound"
          )
        )
    val gpCheck = gp match
      case Left(message) => failure(message)
      case Right(model) =>
        // The factor has to be a factor: `L L' = cov`, checked on the covariance the model builds.
        val at = IArray.tabulate(13)(i => 0.2 * math.cos(i.toDouble))
        val p = Model.constrain(model, at)
        val f = model.derived(p)("f")
        check(f.length == 11, s"the latent field has ${f.length} values, expected 11")
          .and(
            check(
              (0 until f.length).forall(i => f(i).isFinite),
              s"the latent field is not finite: ${f.toList}"
            )
          )
          .and(check(p.rho > 0.0 && p.alpha > 0.0, "the scales are not positive"))
    garchCheck.and(gpCheck)
  }
