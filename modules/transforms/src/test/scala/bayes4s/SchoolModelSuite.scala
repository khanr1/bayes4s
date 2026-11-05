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

import org.apache.commons.numbers.gamma.Erf

import bayes4s.testkit.Approx
import bayes4s.testkit.Fixtures
import bayes4s.testkit.MonteCarlo
import cats.Monad
import cats.effect.IO
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/** The two readings of one model: the density and the simulation.
  *
  * A generative model says the same thing twice — `logPrior` evaluates a prior, `samplePrior`
  * draws from one — and nothing makes them the same prior. A disagreement would invalidate every
  * simulation-based calibration built on it without failing anything, which is why the plan calls
  * this test obligatory and why it is here rather than in phase 6.
  *
  * Two halves, and they assert different things. The **identity** compares `logPrior` against the
  * sum of the law densities at the same point, exactly: that is a statement about the two
  * definitions, with no Monte Carlo in it. The **distribution** compares the draws against the
  * closed-form marginals the priors have. The third half of the plan's test — the same comparison
  * against MCMC samples from `priorOnly` — needs a sampler, and arrives with phase 4.
  */
object SchoolModelSuite extends SimpleIOSuite with Checkers with MonteCarlo with Approx:

  private val n = 100_000
  private val seed = Seed(20261105L)

  private val data: Either[String, EightSchools.Data] =
    Fixtures.posteriordbData("eight_schools-eight_schools_noncentered").flatMap { json =>
      val cursor = json.hcursor
      for
        y <- cursor.get[List[Double]]("y").left.map(_.getMessage)
        sigma <- cursor.get[List[Double]]("sigma").left.map(_.getMessage)
        built <- EightSchools.Data.of(IArray.from(y), IArray.from(sigma)).left.map(_.message)
      yield built
    }

  private def withData(f: EightSchools.Data => weaver.Expectations) =
    data.fold(message => failure(message), f)

  private def withDataIO(f: EightSchools.Data => IO[weaver.Expectations]) =
    data.fold(message => IO.pure(failure(message)), f)

  private def draws[A](program: Rand[A], from: Seed): List[A] =
    Monad[Rand].replicateA(n, program).runA(from).value

  private def ks(xs: List[Double], cdf: Double => Double): Double =
    val sorted = xs.sorted
    val size = sorted.length.toDouble
    sorted.zipWithIndex.map { (x, i) =>
      val f = cdf(x)
      math.max(math.abs(f - i / size), math.abs(f - (i + 1) / size))
    }.max

  /** The same program written from the law values, which is what the generative part stands for. */
  private def fromLaws(schools: Int): Rand[EightSchools.Centred[Double]] =
    val muLaw = Normal.of(0.0, EightSchools.MuScale)
    val tauLaw = HalfCauchy.of(EightSchools.TauScale)
    for
      mu <- muLaw.fold(_ => Monad[Rand].pure(Double.NaN), _.sample[Rand])
      tau <- tauLaw.fold(_ => Monad[Rand].pure(Double.NaN), _.sample[Rand])
      theta <- Normal
        .of(mu, tau)
        .fold(
          _ => Monad[Rand].pure(List.fill(schools)(Double.NaN)),
          law => Monad[Rand].replicateA(schools, law.sample[Rand])
        )
    yield EightSchools.Centred(mu, tau, IArray.from(theta))

  pureTest("samplePrior draws exactly what the law values draw, for the same seed") {
    withData { d =>
      val generative = EightSchools.generative(d)
      val mine = generative.samplePrior[Rand].runA(seed).value
      val theirs = fromLaws(d.schools).runA(seed).value
      check(mine.mu == theirs.mu, s"mu: ${mine.mu} against ${theirs.mu}")
        .and(check(mine.tau == theirs.tau, s"tau: ${mine.tau} against ${theirs.tau}"))
        .and(
          check(
            mine.theta.toList == theirs.theta.toList,
            s"theta: ${mine.theta.toList} against ${theirs.theta.toList}"
          )
        )
    }
  }

  pureTest("simulateData draws exactly what a Gaussian per school draws") {
    withData { d =>
      val generative = EightSchools.generative(d)
      val p = EightSchools.Centred(1.0, 2.0, IArray.tabulate(d.schools)(j => 0.5 * j - 1.0))
      val mine = generative.simulateData[Rand](p).runA(seed).value
      val theirs = (0 until d.schools).toList
        .foldLeft(Monad[Rand].pure(Vector.empty[Double])) { (acc, j) =>
          Normal
            .of(p.theta(j), d.sigma(j))
            .fold(
              _ => acc,
              law => acc.flatMap(ys => law.sample[Rand].map(ys :+ _))
            )
        }
        .runA(seed)
        .value
      check(mine.y.toList == theirs.toList, s"${mine.y.toList} against ${theirs.toList}")
        .and(check(mine.sigma.toList == d.sigma.toList, "the standard errors changed"))
    }
  }

  /** The identity: `logPrior` is the sum of the three laws, at any point at all. */
  test("logPrior is the prior the simulation draws from, term by term") {
    withDataIO { d =>
      val model = EightSchools.centred(d)
      forall(
        Gen.zip(
          Gen.choose(-10.0, 10.0),
          Gen.choose(0.01, 20.0),
          Gen.listOfN(8, Gen.choose(-30.0, 30.0))
        )
      ) { (mu, tau, theta) =>
        val p = EightSchools.Centred(mu, tau, IArray.from(theta))
        val expected = for
          muLaw <- Normal.of(0.0, EightSchools.MuScale)
          tauLaw <- HalfCauchy.of(EightSchools.TauScale)
          thetaLaw <- Normal.of(mu, tau)
        yield muLaw.logPdf(mu) + tauLaw.logPdf(tau) +
          theta.foldLeft(0.0)((total, t) => total + thetaLaw.logPdf(t))
        expected match
          case Left(error) => failure(error.message)
          case Right(value) => closeRel(model.logPrior(p), value, 1e-13, s"logPrior at tau = $tau")
      }
    }
  }

  pureTest("the prior draws have the distribution the prior claims") {
    withData { d =>
      val generative = EightSchools.generative(d)
      val sampled = draws(generative.samplePrior[Rand], seed.fold(7L))
      val mus = sampled.map(_.mu)
      val taus = sampled.map(_.tau)
      val normalCdf =
        (x: Double) => 0.5 * (1.0 + Erf.value(x / (EightSchools.MuScale * math.sqrt(2.0))))
      // A half Cauchy folded at zero: F(x) = 2 atan(x / scale) / pi.
      val halfCauchyCdf = (x: Double) => 2.0 * math.atan(x / EightSchools.TauScale) / math.Pi
      val threshold = 1.95 / math.sqrt(n)
      val muStatistic = ks(mus, normalCdf)
      val tauStatistic = ks(taus, halfCauchyCdf)
      val mean = mus.sum / n
      check(
        muStatistic <= threshold,
        s"mu: Kolmogorov-Smirnov = $muStatistic against $threshold at the 0.1 % point"
      )
        .and(
          check(
            tauStatistic <= threshold,
            s"tau: Kolmogorov-Smirnov = $tauStatistic against $threshold"
          )
        )
        // A half Cauchy has no mean to test, so only mu gets a moment.
        .and(
          withinStandardErrors(
            mean,
            0.0,
            EightSchools.MuScale / math.sqrt(n.toDouble),
            4.0,
            "the mean of mu"
          )
        )
        .and(check(taus.forall(_ >= 0.0), "a negative tau was drawn"))
    }
  }

  /** The two parameterisations are the same posterior, written in different coordinates.
    *
    * `theta = mu + tau z` has Jacobian `tau` per school, so the non-centred density is the centred
    * one plus `J log tau`. If that identity failed, one of the two models would be fitting
    * something else — which is exactly the mistake the non-centred form exists to avoid.
    */
  test("the centred and non-centred models describe the same posterior") {
    withDataIO { d =>
      val centred = EightSchools.centred(d)
      val nonCentred = EightSchools.nonCentred(d)
      forall(
        Gen.zip(
          Gen.choose(-10.0, 10.0),
          Gen.choose(0.05, 15.0),
          Gen.listOfN(8, Gen.choose(-3.0, 3.0))
        )
      ) { (mu, tau, z) =>
        val theta = IArray.tabulate(8)(j => mu + tau * z(j))
        val one = centred.logDensity(EightSchools.Centred(mu, tau, theta)) +
          8.0 * math.log(tau)
        val other = nonCentred.logDensity(EightSchools.NonCentred(mu, tau, IArray.from(z)))
        closeRel(other, one, 1e-12, s"at mu = $mu, tau = $tau")
      }
    }
  }

  pureTest("conditionedOn replaces the data and nothing else") {
    withData { d =>
      val generativeModel = EightSchools.generativeModel(d)
      val simulated = generativeModel.generative
        .simulateData[Rand](EightSchools.Centred(20.0, 5.0, IArray.fill(8)(20.0)))
        .runA(seed)
        .value
      val onSimulated = generativeModel.conditionedOn(simulated)
      val p = EightSchools.Centred(1.0, 2.0, IArray.fill(8)(1.0))
      check(
        generativeModel.model.logLikelihood(p) != onSimulated.logLikelihood(p),
        "the likelihood did not change with the data"
      )
        .and(
          check(
            generativeModel.model.logPrior(p) == onSimulated.logPrior(p),
            "the prior changed with the data"
          )
        )
        .and(
          check(
            generativeModel.model.params.names == onSimulated.params.names,
            "the parameters changed with the data"
          )
        )
        .and(check(simulated.y.toList != d.y.toList, "the simulated data is the observed data"))
    }
  }
