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

/** `Init.Uniform`'s `attempts`: draw several starting points and keep the best one.
  *
  * Why this exists is in `docs/validation.md`, measured on the seed that failed in CI: a single
  * uniform draw put a chain of `arma-arma11` where the log density was **-9.6e111**, the
  * step-size search collapsed to 1e-50, and the chain never recovered. Four candidates, the best
  * kept, and every chain found the posterior.
  *
  * The default is **one** attempt, which is Stan's behaviour and this library's until now. At one
  * attempt the randomness consumed is identical, so a fit does not move — which is the claim this
  * suite pins down, because the alternative would silently change every existing result.
  */
object InitAttemptsSuite extends SimpleIOSuite with Approx:

  final case class Peaked[A](x: A)

  /** A log density with a sharp peak at 3 and a long bad tail, so a start can be clearly poor. */
  private val model: Model[Peaked] = new Model[Peaked]:
    def params: ParamSpec[Peaked] = ParamSpec.real("x").map[Peaked]([A] => (x: A) => Peaked(x))
    def logPrior[A: Real](p: Peaked[A]): A =
      val r = Real[A]
      val d = r.minus(p.x, r.const(3.0))
      r.times(r.const(-50.0), r.times(d, d))
    def logLikelihood[A: Real](p: Peaked[A]): A = Real[A].const(0.0)

  private val target = Model.target(model)

  pureTest("one attempt consumes the same randomness as before, so nothing moves") {
    val once = Mcmc.startingPoint[Rand, Peaked](model, Init.Uniform(2.0), None).runA(Seed(7L)).value
    val explicit =
      Mcmc
        .startingPoint[Rand, Peaked](model, Init.Uniform(2.0, attempts = 1), None)
        .runA(Seed(7L))
        .value
    // The point itself, and what is left of the seed afterwards: a start that consumed a different
    // number of uniforms would move every draw that follows it.
    val afterOnce =
      Mcmc.startingPoint[Rand, Peaked](model, Init.Uniform(2.0), None).runS(Seed(7L)).value
    val afterExplicit =
      Mcmc.startingPoint[Rand, Peaked](model, Init.Uniform(2.0, 1), None).runS(Seed(7L)).value
    check(once.map(_.toList) == explicit.map(_.toList), s"$once against $explicit")
      .and(check(afterOnce.toLong == afterExplicit.toLong, "the seed advanced differently"))
  }

  pureTest("more attempts keep the candidate with the highest log density") {
    val attempts = 12
    val chosen = Mcmc
      .startingPoint[Rand, Peaked](model, Init.Uniform(2.0, attempts), None)
      .runA(Seed(11L))
      .value
    // The same draws, taken by hand, so the test knows what the best one was.
    val candidates = cats
      .Monad[Rand]
      .replicateA(attempts, Rng[Rand].uniform.map(u => IArray(2.0 * (2.0 * u - 1.0))))
      .runA(Seed(11L))
      .value
    val best = candidates.maxBy(q => Point.at(target, q).logDensity)
    chosen match
      case Left(error) => failure(error.message)
      case Right(point) =>
        check(point.toList == best.toList, s"chose ${point.toList}, best is ${best.toList}")
          // And it really is better than most of them: the peak is at 3, so a candidate near 2 is
          // the best available from (-2, 2) and the worst is near -2.
          .and {
            val lps = candidates.map(q => Point.at(target, q).logDensity)
            check(
              Point.at(target, point).logDensity >= lps.max - 1e-12,
              s"the chosen log density is ${Point.at(target, point).logDensity}, best ${lps.max}"
            ).and(
              check(
                lps.max - lps.min > 1.0,
                s"the candidates barely differ: ${lps.min} to ${lps.max}"
              )
            )
          }
  }

  pureTest("a nonsensical number of attempts is refused rather than guessed at") {
    val zero =
      Mcmc.startingPoint[Rand, Peaked](model, Init.Uniform(2.0, 0), None).runA(Seed(1L)).value
    val negative =
      Mcmc.startingPoint[Rand, Peaked](model, Init.Uniform(2.0, -3), None).runA(Seed(1L)).value
    check(zero.isLeft, s"zero attempts gave $zero")
      .and(check(negative.isLeft, s"a negative count gave $negative"))
  }

  pureTest("a candidate whose log density is not finite is never chosen over one that is") {
    // A target that is `NaN` on the negative half, which is what a model built on a square root or
    // a logarithm does outside its domain — and `NaN` compares false with everything, so a naive
    // `maxBy` can return it.
    val halfUsable: DiffFn = new DiffFn:
      def apply[A: Real](q: IArray[A]): A =
        val r = Real[A]
        // `log` of a negative number is `NaN`, and nothing here guards it on purpose.
        r.log(q(0))

    val chosen = Mcmc
      .uniformStart[Rand](halfUsable, dimension = 1, radius = 2.0, attempts = 20)
      .runA(Seed(3L))
      .value
    chosen match
      case Left(error) => failure(error.message)
      case Right(point) =>
        val lp = Point.at(halfUsable, point).logDensity
        check(!lp.isNaN, s"a NaN candidate was chosen: ${point.toList} with log density $lp")
          .and(check(point(0) > 0.0, s"chose ${point(0)}, which is outside the usable half"))
  }
