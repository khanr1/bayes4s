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

/** Calibrating the model's own error on dates already past.
  *
  * The error term exists for the part of the future no component claims to explain, and its scale
  * cannot be read off the fit — the fit has no idea what it is missing. It has to be measured
  * against what actually happened: widen every past forecast by each candidate scale, score the
  * widened ensembles, keep the candidate that scores best.
  *
  * The test rests on the score being **proper**. Forecasts that are certain and observations that
  * are not give a known right answer: the scale that minimises the CRPS is the one the
  * observations were actually generated with. That is a property of the scoring rule, not of this
  * code, which is exactly what makes it a test of this code.
  */
object ModelErrorCalibrationSuite extends SimpleIOSuite with Approx:

  private val dates = 400
  private val rate = 50.0
  private val grid = IArray.tabulate(21)(i => i * 0.05)

  /** Forecasts that are certain, and observations that are not: the error has all of the spread. */
  private def pastDates(trueSigma: Double, seed: Long): List[(IArray[Double], Double)] =
    val program = cats
      .Monad[Rand]
      .replicateA(
        dates,
        Rng[Rand].normal.map(z => ModelError.applied(rate, trueSigma * z))
      )
    val observed = program.runA(Seed(seed)).value
    observed.map(o => (IArray.fill(200)(rate), o))

  test("the calibration finds the scale the observations were generated with") {
    val trueSigma = 0.3
    val calibrated = ModelError.calibrate(
      pastDates(trueSigma, 20261107L),
      grid,
      (forecast, observed) => Crps.ensemble(forecast, observed),
      Seed(7L)
    )
    cats.effect.IO {
      calibrated match
        case Left(error) => failure(error.message)
        case Right(result) =>
          // The grid is in steps of 0.05, so landing within one step of 0.3 is the best the grid
          // can do; the assertion allows two, which is the honest resolution at 400 dates.
          check(
            math.abs(result.sigmaError - trueSigma) <= 0.10 + 1e-12,
            f"the calibration chose ${result.sigmaError}%.3f for a true $trueSigma%.3f"
          )
            .and(check(result.scores.length == grid.length, "a candidate was not scored"))
            // The score has to be worse at both ends than at the optimum, or the grid search found
            // a slope rather than a minimum.
            .and {
              val scores = result.scores
              val best = scores.toList.map(_._2).min
              check(
                scores.head._2 > best && scores(scores.length - 1)._2 > best,
                f"the ends score ${scores.head._2}%.4f and ${scores(scores.length - 1)._2}%.4f " +
                  f"against a best of $best%.4f"
              )
            }
    }
  }

  test("a forecast that already has the right spread needs almost no error") {
    // The observations and the forecast samples come from the same distribution, so the honest
    // answer is that no extra error is needed — the calibration must not add spread to a forecast
    // that is already calibrated.
    val spread = 0.25
    val program = cats
      .Monad[Rand]
      .replicateA(
        dates,
        for
          observed <- Rng[Rand].normal.map(z => ModelError.applied(rate, spread * z))
          ensemble <- cats
            .Monad[Rand]
            .replicateA(
              200,
              Rng[Rand].normal.map(z => ModelError.applied(rate, spread * z))
            )
        yield (IArray.from(ensemble), observed)
      )
    val past = program.runA(Seed(11L)).value
    cats.effect.IO {
      ModelError.calibrate(past, grid, Crps.ensemble, Seed(9L)) match
        case Left(error) => failure(error.message)
        case Right(result) =>
          check(
            result.sigmaError <= 0.10 + 1e-12,
            f"an already-calibrated forecast was widened by ${result.sigmaError}%.3f"
          )
    }
  }

  pureTest("widening is per sample, which changes the spread rather than the position") {
    val forecast = IArray.fill(4000)(10.0)
    val widened = ModelError.widen[Rand](forecast, 0.4).runA(Seed(3L)).value
    val mean = (0 until widened.length).foldLeft(0.0)((s, i) => s + widened(i)) / widened.length
    val distinct = widened.toList.distinct.length
    check(distinct == widened.length, s"only $distinct of ${widened.length} samples differ")
      // A lognormal's mean is above its median, so widening a point mass raises the mean: that is
      // the 8 % at sigma 0.4 the scaladoc warns about, not a bug.
      .and(closeRel(mean, 10.0 * math.exp(0.08), 0.03, "the widened mean"))
      .and(closeRel(Quantile.quantile(widened, 0.5), 10.0, 0.03, "the widened median"))
      .and(
        check(
          ModelError.widen[Rand](forecast, 0.0).runA(Seed(1L)).value.toList == forecast.toList,
          "no error, no change"
        )
      )
      .and(
        check(
          ModelError.widen[Rand](forecast, -1.0).runA(Seed(1L)).value.forall(_.isNaN),
          "a negative scale"
        )
      )
  }

  pureTest("the calibration is a function of its seed, and compares candidates on one noise") {
    // Two properties in one test. The obvious one: the same seed gives the same answer, as
    // everything seeded in this library must. The subtler one is why the seed is derived from the
    // candidate's index rather than drawn afresh: every candidate is widened with the same
    // underlying noise, so the comparison between them is a comparison of scales and not of two
    // draws. A fresh seed per candidate would still work on four hundred dates and would quietly
    // stop being reproducible, which is what this pins.
    val past = List(
      (IArray(1.0, 2.0, 3.0), 2.5),
      (IArray(2.0, 3.0, 4.0), 2.0),
      (IArray(0.5, 1.0, 1.5), 3.0)
    )
    val grid = IArray(0.0, 0.2, 0.4)
    val once = ModelError.calibrate(past, grid, Crps.ensemble, Seed(5L))
    val again = ModelError.calibrate(past, grid, Crps.ensemble, Seed(5L))
    val other = ModelError.calibrate(past, grid, Crps.ensemble, Seed(6L))
    (once, again, other) match
      case (Right(a), Right(b), Right(c)) =>
        check(
          a.scores.toList == b.scores.toList,
          s"the same seed gave ${a.scores.toList} and ${b.scores.toList}"
        )
          .and(
            check(
              a.scores.toList != c.scores.toList,
              "a different seed gave exactly the same scores"
            )
          )
          // The candidate of zero is scored without any noise at all, so its score is the one the
          // unwidened forecast earns — a fixed number, whatever the seed.
          .and(closeRel(a.scores.head._2, c.scores.head._2, 1e-15, "the zero candidate's score"))
      case _ => failure("a calibration that should have succeeded returned a Left")
  }

  pureTest("the calibration refuses what it cannot search") {
    val past = List((IArray(1.0, 2.0), 1.5))
    check(ModelError.calibrate(Nil, grid, Crps.ensemble, Seed(1L)).isLeft, "no dates")
      .and(
        check(
          ModelError.calibrate(past, IArray.empty[Double], Crps.ensemble, Seed(1L)).isLeft,
          "an empty grid was searched"
        )
      )
      // A grid of one candidate is a valid, if pointless, search: it returns that candidate.
      .and {
        ModelError.calibrate(past, IArray(0.2), Crps.ensemble, Seed(1L)) match
          case Left(error) => failure(error.message)
          case Right(result) => check(result.sigmaError == 0.2, "a single candidate was not chosen")
      }
  }
