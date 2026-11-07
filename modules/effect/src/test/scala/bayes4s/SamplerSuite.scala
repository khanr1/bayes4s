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
import cats.effect.IO
import scala.concurrent.duration.*
import weaver.SimpleIOSuite

/** The effectful sampler, against the pure one.
  *
  * Three claims, which are the phase's criteria, and one of them is the reason this module can be
  * trusted at all: **running the chains in parallel in `IO` gives the same numbers as running them
  * sequentially in `State`, bit for bit.** Not the same distribution, not the same summaries — the
  * same doubles. If that held only approximately then nothing fitted at the edge could be
  * reproduced by a pure test, and every other test in the library would stop covering the code
  * anyone actually runs.
  */
object SamplerSuite extends SimpleIOSuite with Approx:

  private val schools = EightSchools.Data
    .of(
      IArray(28.0, 8.0, -3.0, 7.0, -1.0, 1.0, 18.0, 12.0),
      IArray(15.0, 10.0, 16.0, 11.0, 9.0, 11.0, 10.0, 18.0)
    )
    .toOption
    .get
  private val model = EightSchools.nonCentred(schools)
  private val settings = RunSettings(chains = 4, warmup = 150, samples = 100)

  test("the chains in parallel are the chains in State, bit for bit") {
    val seed = Seed(20261107L)
    val pure = for
      chains <- Mcmc.runNuts(model, settings).runA(seed).value
      draws <- Mcmc.draws(model, chains)
    yield draws
    Sampler[IO].run(model, settings, seed).map { parallel =>
      pure match
        case Left(error) => failure(error.message)
        case Right(expected) =>
          val left = expected.flatten
          val right = parallel.flatten
          check(parallel.names.toList == expected.names.toList, "the names differ")
            .and(check(right.length == left.length, s"${right.length} rows against ${left.length}"))
            .and(
              check(
                (0 until left.length)
                  .forall(i => (0 until left(i).length).forall(j => left(i)(j) == right(i)(j))),
                "a value differs between the parallel run and the pure one"
              )
            )
            // And it really is four chains of a hundred: a run that silently dropped one would
            // pass a comparison of the first chain alone.
            .and(check(left.length == 400, s"${left.length} rows for four chains of a hundred"))
    }
  }

  test("a streamed chain is the same chain, and says which iterations were warmup") {
    val seed = Seed(77L)
    val one = RunSettings(chains = 1, warmup = 30, samples = 20)
    for drawn <- Sampler[IO].chain(model, one, chainId = 0, seed).compile.toVector
    yield
      val pure = Mcmc.runNuts(model, one).runA(seed).value
      val warmup = drawn.filter(_.warmup)
      val sampling = drawn.filterNot(_.warmup)
      check(drawn.length == 50, s"${drawn.length} iterations, expected 50")
        .and(check(warmup.length == 30, s"${warmup.length} warmup iterations"))
        .and(check(sampling.length == 20, s"${sampling.length} sampling iterations"))
        .and(
          check(
            drawn.map(_.iteration).toList == (0 until 50).toList,
            "the iterations are misnumbered"
          )
        )
        .and(check(drawn.forall(_.chain == 0), "a draw came from the wrong chain"))
        // The sampling part is the pure chain's draws, named and constrained.
        .and {
          pure match
            case Left(error) => failure(error.message)
            case Right(chains) =>
              val expected = chains.head.draws.map(q => Mcmc.row(model, q))
              check(
                (0 until 20).forall(i =>
                  (0 until expected(i).length).forall(j => expected(i)(j) == sampling(i).values(j))
                ),
                "a streamed draw differs from the folded one"
              )
        }
        // Every draw carries its transition, so a diagnostic can read the tree depth and the
        // divergences of the warmup as well as of the sampling.
        .and(check(drawn.forall(d => d.transition.stepSize > 0.0), "a transition has no step size"))
  }

  test("cancellation lands quickly, and between iterations") {
    // A run of a hundred thousand samples takes minutes; cancelled after a moment it must stop in
    // well under a second. The threshold is two seconds, which is generous enough for a loaded CI
    // machine and still a hundredth of what the run would take — the claim is that cancellation is
    // checked between iterations, not that the machine is fast.
    val enormous = RunSettings(chains = 4, warmup = 1000, samples = 100_000)
    for
      started <- IO.monotonic
      outcome <- Sampler[IO]
        .run(model, enormous, Seed(5L))
        .map(Option.apply)
        .timeoutTo(100.millis, IO.pure(Option.empty[Draws]))
      ended <- IO.monotonic
    yield
      val elapsed = ended - started
      check(outcome.isEmpty, "a hundred thousand samples finished in a tenth of a second")
        .and(
          check(
            elapsed < 2.seconds,
            s"the cancelled run took ${elapsed.toMillis} ms to stop"
          )
        )
  }

  test("a core error becomes an error of F, rather than a value or a silence") {
    // A starting point the target cannot read: the core returns a `Left`, and the edge must raise
    // it. `BayesError` extends `NoStackTrace` for exactly this, so nothing wraps or renames it.
    val impossible = RunSettings(
      chains = 1,
      warmup = 5,
      samples = 5,
      init = Init.Given(
        Map("mu" -> IArray(Double.NaN), "tau" -> IArray(1.0), "z" -> IArray.fill(8)(0.0))
      )
    )
    for
      attempted <- Sampler[IO].run(model, impossible, Seed(1L)).attempt
      streamed <- Sampler[IO].chain(model, impossible, 0, Seed(1L)).compile.toVector.attempt
    yield check(attempted.isLeft, "an impossible start returned draws")
      .and(check(streamed.isLeft, "an impossible start streamed draws"))
      .and {
        attempted match
          case Left(error: BayesError) =>
            check(
              error.message.contains("start"),
              s"the error does not name the starting point: ${error.message}"
            )
          case Left(other) => failure(s"the error is not a BayesError: $other")
          case Right(_) => failure("an impossible start returned draws")
      }
  }
