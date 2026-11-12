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
import bayes4s.testkit.Approx
import bayes4s.testkit.Fixtures
import io.circe.Json
import weaver.SimpleIOSuite

/** The facade against `rethinking.json`.
  *
  * The reference was computed with NumPy and SciPy from the **documentation** of the R package —
  * its published parameterisations — and never from its source, which is GPL (docs/design.md).
  * What is asserted here is therefore that this library's own laws, called with the package's
  * arguments in the package's order, give the package's numbers.
  *
  * Two of the reference's rules are marked `confirm_with_R` and are not yet confirmed: the
  * `coda::HPDinterval` tie-breaking and the WAIC standard error. They are recorded as pending in
  * docs/validation.md rather than quietly asserted as settled.
  */
object FacadeSuite extends SimpleIOSuite with Approx:

  private def densities(json: Json, fn: String): List[(Json, Double)] =
    json.hcursor
      .downField("densities")
      .values
      .toList
      .flatten
      .filter(_.hcursor.get[String]("fn").toOption.contains(fn))
      .map(e =>
        (
          e.hcursor.downField("args").focus.getOrElse(Json.Null),
          e.hcursor.get[Double]("log").getOrElse(Double.NaN)
        )
      )

  private def double(args: Json, field: String): Double =
    args.hcursor.get[Double](field).getOrElse(Double.NaN)

  private def int(args: Json, field: String): Int = args.hcursor.get[Int](field).getOrElse(-1)

  private def vector(args: Json, field: String): IArray[Double] =
    IArray.from(args.hcursor.downField(field).as[List[Double]].getOrElse(Nil))

  private def matrix(args: Json, field: String): IArray[IArray[Double]] =
    IArray.from(
      args.hcursor.downField(field).as[List[List[Double]]].getOrElse(Nil).map(IArray.from)
    )

  private def agrees(obtained: Double, expected: Double): Boolean =
    math.abs(obtained - expected) <= 1e-12 * math.max(1.0, math.abs(expected))

  pureTest("every density matches the package's parameterisation") {
    Fixtures.rethinking match
      case Left(message) => failure(message)
      case Right(json) =>
        val gampois = densities(json, "dgampois").map { (a, expected) =>
          (Rethinking.dgampois(int(a, "x"), double(a, "mu"), double(a, "scale")), expected)
        }
        val betabinom = densities(json, "dbetabinom").map { (a, expected) =>
          (
            Rethinking.dbetabinom(
              int(a, "x"),
              int(a, "size"),
              double(a, "prob"),
              double(a, "theta")
            ),
            expected
          )
        }
        val ordlogit = densities(json, "dordlogit").map { (a, expected) =>
          (Rethinking.dordlogit(int(a, "x"), double(a, "phi"), vector(a, "a")), expected)
        }
        val student = densities(json, "dstudent").map { (a, expected) =>
          (
            Rethinking.dstudent(
              double(a, "x"),
              double(a, "nu"),
              double(a, "mu"),
              double(a, "sigma")
            ),
            expected
          )
        }
        val mvnorm = densities(json, "dmvnorm2").map { (a, expected) =>
          (
            Rethinking.dmvnorm2(
              vector(a, "x"),
              vector(a, "Mu"),
              vector(a, "sigma"),
              matrix(a, "Rho")
            ),
            expected
          )
        }
        val zipois = densities(json, "dzipois").map { (a, expected) =>
          (Rethinking.dzipois(int(a, "x"), double(a, "p"), double(a, "lambda")), expected)
        }
        val all = gampois ++ betabinom ++ ordlogit ++ student ++ mvnorm ++ zipois
        val wrong = all.filterNot(agrees)
        check(all.length == 18, s"${all.length} cases, not 18")
          .and(check(wrong.isEmpty, s"disagreeing: $wrong"))
  }

  pureTest("the helpers are the package's, to the digit") {
    Fixtures.rethinking match
      case Left(message) => failure(message)
      case Right(json) =>
        val helpers = json.hcursor.downField("helpers")
        val x = IArray.from(
          helpers.downField("standardize").downField("x").as[List[Double]].getOrElse(Nil)
        )
        val expected =
          IArray.from(
            helpers.downField("standardize").downField("value").as[List[Double]].getOrElse(Nil)
          )
        val standardized = Rethinking.standardize(x)
        val logits = helpers.downField("inv_logit").values.toList.flatten.map { e =>
          (
            Rethinking.invLogit(e.hcursor.get[Double]("x").getOrElse(Double.NaN)),
            e.hcursor.get[Double]("value").getOrElse(Double.NaN)
          )
        }
        val sums = helpers.downField("log_sum_exp").values.toList.flatten.map { e =>
          val values = IArray.from(e.hcursor.downField("x").as[List[Double]].getOrElse(Nil))
          (Rethinking.logSumExp(values), e.hcursor.get[Double]("value").getOrElse(Double.NaN))
        }
        check(x.length == expected.length && x.length > 0, "no standardize case")
          .and(
            check(
              (0 until x.length).forall(i => agrees(standardized(i), expected(i))),
              s"standardize disagrees: ${standardized.toList.take(3)} against ${expected.toList.take(3)}"
            )
          )
          .and(check(logits.forall(agrees), s"inv_logit disagrees: ${logits.filterNot(agrees)}"))
          .and(check(sums.forall(agrees), s"log_sum_exp disagrees: ${sums.filterNot(agrees)}"))
          // `logit` is the inverse, and the round trip is what makes the pair worth having.
          .and {
            val back =
              List(0.01, 0.3, 0.5, 0.97).map(p => Rethinking.invLogit(Rethinking.logit(p)) - p)
            check(back.forall(d => math.abs(d) < 1e-12), s"the round trip drifts: $back")
          }
          // Standardized values have mean zero and standard deviation one, by construction.
          .and {
            val mean = standardized.sum / standardized.length
            check(math.abs(mean) < 1e-12, s"the mean is $mean")
          }
  }

  pureTest("PI and HPDI reproduce the reference, at the book's 89% default") {
    Fixtures.rethinking match
      case Left(message) => failure(message)
      case Right(json) =>
        val cases = json.hcursor.downField("intervals").downField("cases").values.toList.flatten
        val checked = cases.map { entry =>
          val samples =
            IArray.from(entry.hcursor.downField("samples").as[List[Double]].getOrElse(Nil))
          val prob = entry.hcursor.get[Double]("prob").getOrElse(Double.NaN)
          val pi = entry.hcursor.downField("PI").as[List[Double]].getOrElse(Nil)
          val hpdi = entry.hcursor.downField("HPDI").as[List[Double]].getOrElse(Nil)
          val ourPi = Rethinking.PI(samples, prob)
          val ourHpdi = Rethinking.HPDI(samples, prob)
          (
            agrees(ourPi._1, pi.head) && agrees(ourPi._2, pi(1)),
            agrees(ourHpdi._1, hpdi.head) && agrees(ourHpdi._2, hpdi(1)),
            entry.hcursor.get[String]("samples_name").getOrElse("?") -> prob
          )
        }
        check(cases.length == 9, s"${cases.length} cases, not 9")
          .and(check(checked.forall(_._1), s"PI disagrees on ${checked.filterNot(_._1).map(_._3)}"))
          .and(
            check(checked.forall(_._2), s"HPDI disagrees on ${checked.filterNot(_._2).map(_._3)}")
          )
          // The book's default is 89%, in this module only.
          .and {
            val samples = IArray.tabulate(1000)(i => (i + 0.5) / 1000.0)
            check(Rethinking.PI(samples) == Rethinking.PI(samples, 0.89), "the default is not 0.89")
          }
  }

  pureTest("WAIC is on the deviance scale, and its parts are the book's") {
    Fixtures.rethinking match
      case Left(message) => failure(message)
      case Right(json) =>
        val waic = json.hcursor.downField("waic")
        val rows = waic.downField("log_likelihood").as[List[List[Double]]].getOrElse(Nil)
        // The reference is one row per sample from the posterior; the library wants one row per
        // observation, which is the transpose.
        val observations = rows.head.length
        val logLik =
          IArray.tabulate(observations)(i => IArray.from(rows.map(_(i))))
        val computed = Rethinking.WAIC(logLik)
        val expected = waic.get[Double]("WAIC").getOrElse(Double.NaN)
        val lppd = waic.get[Double]("lppd").getOrElse(Double.NaN)
        val penalty = waic.get[Double]("penalty").getOrElse(Double.NaN)
        val se = waic.get[Double]("std_err").getOrElse(Double.NaN)
        check(rows.length == 4000 && observations == 20, s"${rows.length} by $observations")
          .and(check(agrees(computed.lppd, lppd), s"lppd ${computed.lppd} against $lppd"))
          .and(
            check(
              agrees(computed.penalty, penalty),
              s"penalty ${computed.penalty} against $penalty"
            )
          )
          .and(check(agrees(computed.waic, expected), s"WAIC ${computed.waic} against $expected"))
          .and(
            check(agrees(computed.standardError, se), s"se ${computed.standardError} against $se")
          )
          // Deviance scale: `-2 (lppd - penalty)`, so a better fit is a *smaller* number — the
          // opposite of `elpd`, and the sign error this test exists to catch.
          .and(check(computed.waic > 0.0, "a deviance that is not positive here"))
  }

  pureTest("quap is exact on a Gaussian posterior and analytic on a Gamma kernel") {
    Fixtures.rethinking match
      case Left(message) => failure(message)
      case Right(json) =>
        val cases = json.hcursor.downField("quap").downField("cases").values.toList.flatten
        val gaussian = cases.head
        val y = IArray.from(gaussian.hcursor.downField("y").as[List[Double]].getOrElse(Nil))
        val expectedMode =
          gaussian.hcursor.downField("mode").get[Double]("mu").getOrElse(Double.NaN)
        val expectedSd = gaussian.hcursor.downField("sd").get[Double]("mu").getOrElse(Double.NaN)

        // y ~ Gaussian(mu, 1), mu ~ Gaussian(0, 3): the log posterior, as the book writes it.
        val logPosterior = (p: IArray[Double]) =>
          LogDensity.normal(y, IArray.fill(y.length)(p(0)), 1.0) + LogDensity.normal(p(0), 0.0, 3.0)
        val fitted = Rethinking.quap(logPosterior, IArray(0.0), List("mu"))

        val gamma = cases(1)
        val gammaMode = gamma.hcursor.downField("mode").get[Double]("lambda").getOrElse(Double.NaN)
        val gammaSd = gamma.hcursor.downField("sd").get[Double]("lambda").getOrElse(Double.NaN)
        // The Gamma(7.5, 1.6) kernel, on the parameter's own scale as quap works.
        val gammaKernel = (p: IArray[Double]) =>
          if p(0) <= 0.0 then Double.NegativeInfinity
          else 6.5 * math.log(p(0)) - 1.6 * p(0)
        val gammaFit = Rethinking.quap(gammaKernel, IArray(3.0), List("lambda"))

        (fitted, gammaFit) match
          case (Right(a), Right(b)) =>
            // The Gaussian case is exact: the quadratic approximation of a quadratic.
            close(a.mode(0), expectedMode, 1e-8, s"the Gaussian mode is ${a.mode(0)}")
              .and(
                close(
                  a.standardDeviation(0),
                  expectedSd,
                  1e-6,
                  s"its sd is ${a.standardDeviation(0)}"
                )
              )
              // The Gamma case is not exact, but its mode and curvature are analytic.
              .and(close(b.mode(0), gammaMode, 1e-6, s"the Gamma mode is ${b.mode(0)}"))
              .and(
                close(b.standardDeviation(0), gammaSd, 1e-5, s"its sd is ${b.standardDeviation(0)}")
              )
          case other => failure(s"a fit failed: $other")
  }

  pureTest("quap reports rather than hides a posterior with no mode") {
    // A flat log posterior has no mode to find and no curvature to invert.
    val flat = (_: IArray[Double]) => 0.0
    // An increasing one runs away.
    val runaway = (p: IArray[Double]) => p(0)
    check(
      Rethinking.quap(flat, IArray(0.0), List("mu")).isLeft,
      "a flat posterior was approximated"
    )
      .and(
        check(
          Rethinking.quap(runaway, IArray(0.0), List("mu")).isLeft,
          "a runaway was approximated"
        )
      )
      .and {
        val mismatched =
          Rethinking.quap((p: IArray[Double]) => -p(0) * p(0), IArray(0.0), List("a", "b"))
        check(mismatched.isLeft, "a name per parameter was not required")
      }
  }
