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
import bayes4s.testkit.Fixtures
import bayes4s.testkit.MonteCarlo
import cats.Monad
import io.circe.Json
import weaver.SimpleIOSuite

/** The statistics a plot needs, against ArviZ and against exact arithmetic.
  *
  * Three of these carry names that mean different formulas in different libraries — Silverman's
  * rule is not the same number in SciPy, in R and in ArviZ — so the reference is explicit:
  * ArviZ's, because the catalogue is modelled on ArviZ's and bayesplot's. A density drawn with
  * the wrong rule looks entirely plausible and is a fifth too wide.
  *
  * The **values** of the kernel estimate are checked against an exact sum of Gaussians computed in
  * numpy rather than against ArviZ's own estimate, which bins onto a grid: comparing against the
  * binned version would be testing the binning, and this implementation does not bin.
  */
object GraphicsSuite extends SimpleIOSuite with Approx with MonteCarlo:

  private def fixture: Either[String, Json] = Fixtures.plotStatistics

  private def doubles(json: Json, path: List[String]): IArray[Double] =
    val at = path.foldLeft(json.hcursor: io.circe.ACursor)((c, f) => c.downField(f))
    IArray.from(at.as[List[Double]].getOrElse(Nil))

  private def number(json: Json, path: List[String]): Double =
    val at = path.foldLeft(json.hcursor: io.circe.ACursor)((c, f) => c.downField(f))
    at.as[Double].getOrElse(Double.NaN)

  test("the bandwidth rules are ArviZ's, on three shapes of sample") {
    fixture match
      case Left(error) => cats.effect.IO.pure(failure(error))
      case Right(json) =>
        cats.effect.IO {
          val checks = List("gaussian", "skewed", "bimodal").flatMap { shape =>
            val sample = doubles(json, List("samples", shape))
            List(
              ("silverman", Bandwidth.Silverman),
              ("scott", Bandwidth.Scott),
              ("isj", Bandwidth.Isj)
            ).map { (name, rule) =>
              closeRel(
                Density.bandwidth(sample, rule),
                number(json, List("bandwidth", shape, name)),
                1e-10,
                s"$name on the $shape sample"
              )
            }
          }
          check(checks.length == 9, "not every rule was checked").and(checks.reduce(_.and(_)))
        }
  }

  test("the density is the exact sum of Gaussians, point for point") {
    fixture match
      case Left(error) => cats.effect.IO.pure(failure(error))
      case Right(json) =>
        cats.effect.IO {
          val checks = List("gaussian", "skewed", "bimodal").map { shape =>
            val sample = doubles(json, List("samples", shape))
            val grid = doubles(json, List("density", shape, "grid"))
            val bandwidth = number(json, List("density", shape, "bandwidth"))
            val expected = doubles(json, List("density", shape, "density"))
            val got = Density.gaussian(sample, bandwidth, grid)
            (0 until grid.length)
              .map(i =>
                closeRel(got(i), expected(i), 1e-12, s"the $shape density at grid point $i")
              )
              .reduce(_.and(_))
          }
          checks.reduce(_.and(_))
        }
  }

  test("reflecting at a bound keeps the mass that would otherwise leak past it") {
    fixture match
      case Left(error) => cats.effect.IO.pure(failure(error))
      case Right(json) =>
        cats.effect.IO {
          val sample = doubles(json, List("samples", "skewed"))
          val grid = doubles(json, List("densityReflected", "grid"))
          val bandwidth = number(json, List("densityReflected", "bandwidth"))
          val expected = doubles(json, List("densityReflected", "density"))
          val unreflected = doubles(json, List("densityReflected", "unreflected"))
          val got = Density.gaussian(sample, bandwidth, grid, reflectAt = Some(0.0))
          val plain = Density.gaussian(sample, bandwidth, grid)
          (0 until grid.length)
            .map(i => closeRel(got(i), expected(i), 1e-12, s"the reflected density at point $i"))
            .reduce(_.and(_))
            .and(
              (0 until grid.length)
                .map(i =>
                  closeRel(plain(i), unreflected(i), 1e-12, s"the plain density at point $i")
                )
                .reduce(_.and(_))
            )
            // At the bound itself the reflection doubles the density, which is the whole point:
            // without it a posterior pressed against zero looks like one that is not.
            .and(
              check(
                got(0) > 1.5 * plain(0),
                f"at the bound the reflected density is ${got(0)}%.5f against ${plain(0)}%.5f"
              )
            )
        }
  }

  test("the pooled ranks are ArviZ's, with ties broken by position") {
    fixture match
      case Left(error) => cats.effect.IO.pure(failure(error))
      case Right(json) =>
        cats.effect.IO {
          val rows = json.hcursor
            .downField("ranks")
            .downField("chains")
            .as[List[List[Double]]]
            .getOrElse(Nil)
          val expected = json.hcursor
            .downField("ranks")
            .downField("pooled")
            .as[List[List[Double]]]
            .getOrElse(Nil)
          val got = Ranks.pooled(IArray.from(rows.map(r => IArray.from(r))))
          val checks = for
            c <- rows.indices.toList
            i <- rows(c).indices.toList
          yield closeRel(got(c)(i), expected(c)(i), 1e-12, s"the rank of chain $c, draw $i")
          check(got.length == rows.length, s"${got.length} chains of ranks")
            .and(checks.reduce(_.and(_)))
            // The fixture has three values of 5.0 on purpose, and ArviZ gives them 8, 9 and 10
            // rather than 9 three times: its ranking assumes no ties and breaks them by position.
            // Averaging them would be the other defensible convention and is what rank
            // normalisation does — the two coexist, and this one has to match the reference.
            .and(check(expected.flatten.count(_ == 9.0) == 1, "the fixture no longer has its tie"))
        }
  }

  pureTest("the rank histogram puts every draw in exactly one bin") {
    val chains = IArray.tabulate(4)(c => IArray.tabulate(50)(i => math.sin(c * 10.0 + i)))
    val counts = Ranks.histogram(chains, bins = 10)
    check(counts.length == 4, s"${counts.length} chains")
      .and(check(counts.forall(_.length == 10), "a chain has the wrong number of bins"))
      .and(check(counts.map(_.sum).sum == 200, s"${counts.map(_.sum).sum} draws binned of 200"))
      // Chains that are not interchangeable pile their ranks in different bins, which is the
      // whole diagnostic: a chain stuck high has all its ranks at the top.
      .and {
        val stuck =
          IArray(IArray.tabulate(50)(i => i.toDouble), IArray.tabulate(50)(i => 1000.0 + i))
        val piled = Ranks.histogram(stuck, bins = 4)
        check(
          piled(0).take(2).sum == 50 && piled(1).drop(2).sum == 50,
          s"the piled histogram is ${piled.map(_.toList).toList}"
        )
      }
  }

  test("the empirical cumulative function and its pointwise band are the exact ones") {
    fixture match
      case Left(error) => cats.effect.IO.pure(failure(error))
      case Right(json) =>
        cats.effect.IO {
          val sample = doubles(json, List("ecdf", "sample"))
          val points = doubles(json, List("ecdf", "points"))
          val expected = doubles(json, List("ecdf", "values"))
          val reference = doubles(json, List("ecdf", "referenceCdf"))
          val lower = doubles(json, List("ecdf", "pointwiseLower"))
          val upper = doubles(json, List("ecdf", "pointwiseUpper"))
          val got = Ecdf.values(sample, points)
          val band = Ecdf.pointwiseBand(sample.length, points, reference, 0.95)
          (0 until points.length)
            .map(i => closeRel(got(i), expected(i), 1e-12, s"the cumulative value at point $i"))
            .reduce(_.and(_))
            .and(
              (0 until points.length)
                .map(i => closeRel(band.lower(i), lower(i), 1e-12, s"the lower band at point $i"))
                .reduce(_.and(_))
            )
            .and(
              (0 until points.length)
                .map(i => closeRel(band.upper(i), upper(i), 1e-12, s"the upper band at point $i"))
                .reduce(_.and(_))
            )
        }
  }

  test("the simultaneous band covers what it says, and the pointwise one does not") {
    // The criterion of 8.2, and the reason both bands exist. Fresh uniform samples: 95 % of whole
    // curves must stay inside the simultaneous band. The pointwise band, drawn for the same
    // curves, lets far more than 5 % of them out somewhere — which is exactly the mistake it
    // invites, and why the catalogue draws the simultaneous one.
    val n = 60
    val points = IArray.tabulate(19)(i => (i + 1) / 20.0)
    val reference = points
    val replications = 2000
    val simultaneous = Ecdf.simultaneousBand(n, points, reference, 0.95, Seed(20261107L), 3000)
    val pointwise = Ecdf.pointwiseBand(n, points, reference, 0.95)
    val program = Monad[Rand].replicateA(
      replications,
      Monad[Rand].replicateA(n, Rng[Rand].uniform).map { drawn =>
        val sample = IArray.from(drawn)
        val values = Ecdf.values(sample, points)
        val insideSimultaneous =
          (0 until points.length)
            .forall(i => values(i) >= simultaneous.lower(i) && values(i) <= simultaneous.upper(i))
        val insidePointwise =
          (0 until points.length)
            .forall(i => values(i) >= pointwise.lower(i) && values(i) <= pointwise.upper(i))
        (insideSimultaneous, insidePointwise)
      }
    )
    val outcomes = program.runA(Seed(99L)).value
    val covered = outcomes.count(_._1).toDouble / replications
    val coveredPointwise = outcomes.count(_._2).toDouble / replications
    cats.effect.IO {
      withinStandardErrors(
        covered,
        0.95,
        math.sqrt(0.95 * 0.05 / replications),
        4.0,
        "the simultaneous band's coverage"
      )
        .and(
          check(
            coveredPointwise < 0.85,
            f"the pointwise band covered ${coveredPointwise}%.3f of whole curves, so the two " +
              "bands are not telling different stories"
          )
        )
    }
  }
