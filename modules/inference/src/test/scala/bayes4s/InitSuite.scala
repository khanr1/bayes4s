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

/** Where a chain starts, and why it matters.
  *
  * A start far from the posterior is not only slow. On a model with a latent rate the gradient
  * there points nowhere useful, the step-size heuristic drives the step towards zero, and the
  * chain never arrives. `Init.PriorMedian` is the answer, and it has to work both for a model that
  * carries its generative part and for one that does not — the second case by sampling the prior
  * itself, which is what the specification asks for.
  */
object InitSuite extends SimpleIOSuite with Approx:

  private val data = EightSchools.Data.of(
    IArray(28.0, 8.0, -3.0, 7.0, -1.0, 1.0, 18.0, 12.0),
    IArray(15.0, 10.0, 16.0, 11.0, 9.0, 11.0, 10.0, 18.0)
  )

  private def withData(f: EightSchools.Data => weaver.Expectations) =
    data.fold(error => failure(error.message), f)

  pureTest("a uniform start lands inside its radius, on the unconstrained space") {
    withData { d =>
      val model = EightSchools.centred(d)
      val start = Mcmc
        .startingPoint[Rand, EightSchools.Centred](model, Init.Uniform(2.0), None)
        .runA(Seed(5L))
        .value
      start match
        case Left(error) => failure(error.message)
        case Right(point) =>
          check(point.length == 10, s"${point.length} coordinates, expected 10")
            .and(
              check(
                (0 until point.length).forall(i => math.abs(point(i)) <= 2.0),
                s"a coordinate left the radius: ${point.toList}"
              )
            )
            .and(
              check(
                (0 until point.length).exists(i => math.abs(point(i)) > 0.2),
                "every coordinate is at the origin, so nothing was drawn"
              )
            )
            // The whole point of a uniform start on the *unconstrained* space: tau comes out
            // positive whatever was drawn.
            .and(check(Model.constrain(model, point).tau > 0.0, "tau is not positive"))
    }
  }

  pureTest("given values are unconstrained, and a value outside its constraint is refused") {
    withData { d =>
      val model = EightSchools.centred(d)
      val values = Map(
        "mu" -> IArray(4.0),
        "tau" -> IArray(3.0),
        "theta" -> IArray.fill(8)(4.0)
      )
      val found = Mcmc
        .startingPoint[Rand, EightSchools.Centred](model, Init.Given(values), None)
        .runA(Seed(6L))
        .value
      val refused = Mcmc
        .startingPoint[Rand, EightSchools.Centred](
          model,
          Init.Given(values.updated("tau", IArray(-1.0))),
          None
        )
        .runA(Seed(6L))
        .value
      found match
        case Left(error) => failure(error.message)
        case Right(point) =>
          val read = Model.constrain(model, point)
          closeRel(read.mu, 4.0, 1e-12, "mu came back")
            .and(closeRel(read.tau, 3.0, 1e-12, "tau came back"))
            .and(check(refused.isLeft, s"a negative tau was accepted: $refused"))
    }
  }

  /** The generative route: draws from the prior, named, unconstrained, and the median taken.
    *
    * `tau` has a half-Cauchy(5) prior whose median is 5, so the median start should put `tau` near
    * there — and `mu`, whose prior is centred, near zero.
    */
  pureTest("the prior median, from a model that carries its generative part") {
    withData { d =>
      val model = EightSchools.centred(d)
      val generative = EightSchools.generative(d)
      val start = Mcmc
        .startingPoint[Rand, EightSchools.Centred](
          model,
          Init.PriorMedian(400),
          Some(generative)
        )
        .runA(Seed(7L))
        .value
      start match
        case Left(error) => failure(error.message)
        case Right(point) =>
          val read = Model.constrain(model, point)
          check(math.abs(read.mu) < 1.0, s"mu started at ${read.mu}, expected near 0")
            .and(
              check(
                read.tau > 2.0 && read.tau < 12.0,
                s"tau started at ${read.tau}, expected near the half-Cauchy median of 5"
              )
            )
            .and(
              check(
                Reverse.value(Model.target(model), point).isFinite,
                "the density at the prior median is not a number"
              )
            )
    }
  }

  /** The other route: no generative part, so the prior is sampled by the sampler itself. */
  pureTest("the prior median, from a model with no generative part") {
    withData { d =>
      val model = EightSchools.centred(d)
      val start = Mcmc
        .startingPoint[Rand, EightSchools.Centred](model, Init.PriorMedian(200), None)
        .runA(Seed(8L))
        .value
      start match
        case Left(error) => failure(error.message)
        case Right(point) =>
          val read = Model.constrain(model, point)
          check(point.length == 10, s"${point.length} coordinates")
            .and(check(read.tau > 0.0, s"tau started at ${read.tau}"))
            .and(
              check(
                math.abs(read.mu) < 6.0,
                s"mu started at ${read.mu}, which is far outside its prior"
              )
            )
            .and(
              check(
                Reverse.value(Model.target(model), point).isFinite,
                "the density at the prior median is not a number"
              )
            )
    }
  }

  /** A model whose prior start matters: a latent rate whose likelihood is nothing at all far from
    * the mode. Both inits have to produce a chain that arrives.
    */
  pureTest("a chain started at the prior median samples the same posterior as a uniform one") {
    withData { d =>
      val model = EightSchools.nonCentred(d)
      def meanOfMu(init: Init): Either[BayesError, Double] =
        Mcmc
          .runNuts(model, RunSettings(chains = 2, warmup = 600, samples = 600, init = init))
          .runA(Seed(20261105L))
          .value
          .map { chains =>
            val mus = chains.flatMap(_.draws.map(q => Model.constrain(model, q).mu))
            mus.sum / mus.length
          }
      (meanOfMu(Init.Uniform(2.0)), meanOfMu(Init.PriorMedian(100))) match
        case (Right(uniform), Right(median)) =>
          check(
            math.abs(uniform - median) < 1.0,
            s"the two inits gave posterior means $uniform and $median"
          ).and(check(math.abs(uniform - 4.4) < 1.0, s"the uniform start gave $uniform"))
        case (Left(error), _) => failure(s"uniform: ${error.message}")
        case (_, Left(error)) => failure(s"prior median: ${error.message}")
    }
  }
