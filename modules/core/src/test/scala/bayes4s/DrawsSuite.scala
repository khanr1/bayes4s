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
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/** Posterior samples as a rectangle, and the three ways of looking at one.
  *
  * The invariant is the point: every chain the same length, every iteration the same width as
  * there are names, and no name twice. A ragged `Draws` would be summarised without complaint and
  * the summary would be of something that is not a posterior, so the smart constructor is the only
  * way in.
  */
object DrawsSuite extends SimpleIOSuite with Checkers with Approx:

  private val names = IArray("lp__", "mu", "tau")

  private def rectangle(chains: Int, iterations: Int): IArray[IArray[IArray[Double]]] =
    IArray.tabulate(chains) { c =>
      IArray.tabulate(iterations)(i => IArray(-1.0 * c, 0.1 * i, 2.0 + c + 0.01 * i))
    }

  pureTest("a rectangle is accepted and read three ways") {
    Draws.of(names, rectangle(3, 5)) match
      case Left(error) => failure(error.message)
      case Right(draws) =>
        val pooled = draws.column("mu")
        val byChain = draws.byChain("tau")
        val flat = draws.flatten
        check(draws.chainCount == 3 && draws.iterations == 5 && draws.parameters == 3, "the shape")
          .and(
            pooled.fold(
              error => failure(error.message),
              values =>
                // Pooled in chain order: the three chains' five draws, one after another.
                check(values.length == 15, s"${values.length} pooled values")
                  .and(closeRel(values(0), 0.0, 1e-15, "the first draw of mu"))
                  .and(closeRel(values(5), 0.0, 1e-15, "the first draw of mu in chain 1"))
                  .and(closeRel(values(6), 0.1, 1e-15, "the second draw of mu in chain 1"))
            )
          )
          .and(
            byChain.fold(
              error => failure(error.message),
              chains =>
                check(chains.length == 3, s"${chains.length} chains")
                  .and(check(chains(0).length == 5, s"${chains(0).length} draws a chain"))
                  .and(closeRel(chains(2)(0), 4.0, 1e-15, "the first draw of tau in chain 2"))
            )
          )
          .and(check(flat.length == 15, s"${flat.length} flattened draws"))
          .and(check(flat(6).length == 3, "a flattened draw has the wrong width"))
  }

  pureTest("a name the samples do not have is an error, not an empty answer") {
    Draws.of(names, rectangle(2, 3)) match
      case Left(error) => failure(error.message)
      case Right(draws) =>
        check(
          draws.column("theta") == Left(BayesError.UnknownParameter("theta")),
          s"an unknown column gave ${draws.column("theta")}"
        ).and(
          check(
            draws.byChain("theta") == Left(BayesError.UnknownParameter("theta")),
            "and so should byChain"
          )
        )
  }

  pureTest("a shape that is not a rectangle is refused, and says where") {
    val ragged = IArray(
      IArray(IArray(1.0, 2.0, 3.0), IArray(1.0, 2.0, 3.0)),
      IArray(IArray(1.0, 2.0, 3.0))
    )
    val narrow = IArray(IArray(IArray(1.0, 2.0), IArray(1.0, 2.0, 3.0)))
    val twice = IArray("mu", "mu")
    check(Draws.of(names, ragged).isLeft, "a chain of a different length was accepted")
      .and(
        check(
          Draws.of(names, ragged).left.exists(_.message.contains("chain 1")),
          s"the error does not say which chain: ${Draws.of(names, ragged)}"
        )
      )
      .and(check(Draws.of(names, narrow).isLeft, "a draw of the wrong width was accepted"))
      .and(
        check(
          Draws.of(twice, IArray(IArray(IArray(1.0, 2.0)))) ==
            Left(BayesError.DuplicateParameter("mu")),
          "two columns of one name were accepted"
        )
      )
  }

  /** What a predictive simulation asks for: several futures per posterior sample, the sample held
    * fixed across them.
    */
  test("repeating each draw keeps the samples in place") {
    forall(Gen.zip(Gen.choose(1, 4), Gen.choose(1, 6), Gen.choose(1, 5))) {
      (chains, iterations, perDraw) =>
        Draws.of(names, rectangle(chains, iterations)) match
          case Left(error) => failure(error.message)
          case Right(draws) =>
            val repeated = draws.repeatEach(perDraw)
            check(
              repeated.iterations == iterations * perDraw,
              s"${repeated.iterations} iterations for $iterations times $perDraw"
            )
              .and(check(repeated.chainCount == chains, "the chains changed"))
              .and(check(repeated.names.toList == names.toList, "the names changed"))
              .and(forEach(for
                c <- (0 until chains).toList
                i <- 0 until iterations * perDraw
              yield (c, i)) { (c, i) =>
                check(
                  repeated.chains(c)(i).toList == draws.chains(c)(i / perDraw).toList,
                  s"chain $c, repeated draw $i is not the original draw ${i / perDraw}"
                )
              })
    }
  }

  pureTest("repeating each draw once changes nothing, and so does repeating it zero times") {
    Draws.of(names, rectangle(2, 3)) match
      case Left(error) => failure(error.message)
      case Right(draws) =>
        check(draws.repeatEach(1) == draws, "repeating once changed the samples")
          .and(check(draws.repeatEach(0) == draws, "repeating zero times is not a request"))
          .and(check(draws.repeatEach(-1) == draws, "nor is a negative count"))
  }

  pureTest("a single chain is a rectangle of one row") {
    Draws.single(names, IArray(IArray(1.0, 2.0, 3.0), IArray(4.0, 5.0, 6.0))) match
      case Left(error) => failure(error.message)
      case Right(draws) =>
        check(draws.chainCount == 1 && draws.iterations == 2, "the shape")
          .and(
            draws
              .column("tau")
              .fold(
                error => failure(error.message),
                values => check(values.toList == List(3.0, 6.0), "the column")
              )
          )
  }
