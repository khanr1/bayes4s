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
import cats.syntax.all.*
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

/** CmdStan's CSV, read and written.
  *
  * The round trip is asserted **exactly**, not to a tolerance: a posterior that changes in its
  * sixteenth digit when it passes through a file is a posterior whose diagnostics change with it,
  * and the shortest decimal form of a double reads back as the same double. The other half is that
  * a real CmdStan file parses — comments before the header, comments after it, and the adaptation
  * block in between — which the four Bernoulli chains of the fixture provide.
  */
object StanCsvSuite extends SimpleIOSuite with Checkers with Approx:

  pureTest("a real CmdStan file parses, comments and all") {
    Fixtures.bernoulliCsv(1) match
      case Left(message) => failure(message)
      case Right(lines) =>
        StanCsv.parse(lines) match
          case Left(error) => failure(error.message)
          case Right(draws) =>
            val expected = List(
              "lp__",
              "accept_stat__",
              "stepsize__",
              "treedepth__",
              "n_leapfrog__",
              "divergent__",
              "energy__",
              "theta"
            )
            check(draws.names.toList == expected, s"the columns are ${draws.names.toList}")
              .and(check(draws.iterations == 1000, s"${draws.iterations} draws, expected 1000"))
              .and(check(draws.chainCount == 1, s"${draws.chainCount} chains, expected 1"))
              // The adaptation block sits *after* the header, so a parser that stopped skipping
              // comments at the header would read '# Step size = 0.883328' as a row.
              .and(
                check(
                  lines.exists(_.startsWith("# Adaptation terminated")),
                  "the fixture no longer has comments after its header"
                )
              )
              .and(
                draws
                  .column("theta")
                  .fold(
                    error => failure(error.message),
                    theta =>
                      closeRel(theta(0), 0.208532, 1e-12, "the first draw of theta")
                        .and(
                          check(
                            (0 until theta.length).forall(i => theta(i) > 0.0 && theta(i) < 1.0),
                            "a draw of theta left the unit interval"
                          )
                        )
                  )
              )
  }

  pureTest("the four chains parse as one fit, and its columns agree") {
    (1 to 4).toList.traverse(Fixtures.bernoulliCsv) match
      case Left(message) => failure(message)
      case Right(files) =>
        StanCsv.parseAll(files) match
          case Left(error) => failure(error.message)
          case Right(draws) =>
            check(draws.chainCount == 4, s"${draws.chainCount} chains")
              .and(check(draws.iterations == 1000, s"${draws.iterations} draws a chain"))
              .and(
                draws
                  .byChain("theta")
                  .fold(
                    error => failure(error.message),
                    chains =>
                      // The same four chains the diagnostics suite reads through its own reader:
                      // two parsers, one answer.
                      Fixtures
                        .bernoulliChains("theta")
                        .fold(
                          failure,
                          independent =>
                            forEach((0 until 4).toList) { c =>
                              check(
                                chains(c).toList == independent(c).toList,
                                s"chain $c differs from what the fixture's own reader gives"
                              )
                            }
                        )
                  )
              )
  }

  /** Rendered and parsed again, exactly, for arbitrary values — the awkward ones included. */
  test("a round trip through the file changes nothing at all") {
    val awkward: Gen[Double] = Gen.oneOf(
      Gen.oneOf(
        0.0,
        -0.0,
        1.0,
        -1.0,
        Double.MinPositiveValue,
        Double.MaxValue,
        Double.MinValue,
        1e-320,
        1e300,
        Double.NaN,
        Double.PositiveInfinity,
        Double.NegativeInfinity
      ),
      Gen.choose(-1e6, 1e6)
    )
    forall(Gen.listOfN(12, awkward)) { values =>
      val names = IArray("lp__", "mu", "sigma")
      val chain = IArray.tabulate(4)(i => IArray.tabulate(3)(j => values(i * 3 + j)))
      Draws.single(names, chain) match
        case Left(error) => failure(error.message)
        case Right(draws) =>
          StanCsv.parse(StanCsv.render(draws)) match
            case Left(error) => failure(error.message)
            case Right(back) =>
              check(
                back.names.toList == names.toList,
                s"the names came back as ${back.names.toList}"
              )
                .and(check(back.iterations == 4, s"${back.iterations} draws came back"))
                .and(forEach(for
                  i <- (0 until 4).toList
                  j <- 0 until 3
                yield (i, j)) { (i, j) =>
                  val original = draws.chains(0)(i)(j)
                  val returned = back.chains(0)(i)(j)
                  check(
                    sameIeee(returned, original),
                    s"draw $i, value $j: $returned came back for $original"
                  )
                })
    }
  }

  pureTest("a fit of four chains round-trips as four files") {
    val names = IArray("lp__", "theta")
    val chains = IArray.tabulate(4) { c =>
      IArray.tabulate(50)(i => IArray(-1.0 * c - i, 0.1 * c + 0.01 * i))
    }
    Draws.of(names, chains) match
      case Left(error) => failure(error.message)
      case Right(draws) =>
        val files = StanCsv.renderAll(draws)
        StanCsv.parseAll(files) match
          case Left(error) => failure(error.message)
          case Right(back) =>
            check(files.length == 4, s"${files.length} files rendered")
              .and(check(back.chainCount == 4, s"${back.chainCount} chains came back"))
              .and(forEach(for
                c <- (0 until 4).toList
                i <- 0 until 50
                j <- 0 until 2
              yield (c, i, j)) { (c, i, j) =>
                check(
                  back.chains(c)(i)(j) == draws.chains(c)(i)(j),
                  s"chain $c, draw $i, value $j changed"
                )
              })
              // Each file says which chain it is, which is what CmdStan's own tools read.
              .and(
                check(
                  files.zipWithIndex.forall((lines, c) => lines.exists(_ == s"# id = ${c + 1}")),
                  "a rendered file does not say which chain it is"
                )
              )
  }

  /** The structural rules CmdStan's own reader enforces, asserted on what we write.
    *
    * These are not guessed: `reference/check_cmdstan_csv.py` ran `cmdstanpy.from_csv` against a
    * file this renderer produced, and each rule below is one the reader actually applies —
    * including the two that the first version of the renderer broke. `cmdstanpy` finds the draws
    * by locating four blocks in order and refuses a file missing any of them, and it validates the
    * configuration it read, so a declared warmup of zero with the adaptation engaged is rejected.
    * docs/validation.md records both.
    */
  pureTest("what we render satisfies the four rules CmdStan's own reader applies") {
    val names = IArray(
      "lp__",
      "accept_stat__",
      "stepsize__",
      "treedepth__",
      "n_leapfrog__",
      "divergent__",
      "energy__",
      "theta"
    )
    val chains = IArray(IArray.tabulate(10)(i => IArray.tabulate(8)(j => 0.1 * i + j)))
    Draws.of(names, chains) match
      case Left(error) => failure(error.message)
      case Right(draws) =>
        val lines = StanCsv.render(draws)
        val headerAt = lines.indexWhere(!_.startsWith("#"))
        val rows = lines.zipWithIndex.filter((line, i) => i > headerAt && !line.startsWith("#"))
        val lastRow = rows.map(_._2).max
        check(headerAt >= 0, "there is no header line")
          // 1. The header begins with `lp__`, which is how the reader finds it at all.
          .and(check(lines(headerAt).startsWith("lp__"), s"the header is '${lines(headerAt)}'"))
          // 2. A comment block follows the header: the adaptation, with a step size and a metric.
          .and(check(lines(headerAt + 1).startsWith("#"), "no comment follows the header"))
          .and(check(lines.contains("# Adaptation terminated"), "no adaptation marker"))
          .and(
            check(
              lines.exists(_.startsWith("# Step size = ")),
              "the adaptation block carries no step size"
            )
          )
          .and(
            check(
              lines.exists(_.startsWith("# Diagonal elements of inverse mass matrix:")),
              "the adaptation block carries no mass matrix"
            )
          )
          // 3. The rows, all of the same width as the header.
          .and(check(rows.length == 10, s"${rows.length} rows, expected 10"))
          .and(
            check(
              rows.forall((line, _) => line.split(",", -1).length == 8),
              "a row has the wrong number of values"
            )
          )
          // 4. A comment block after the rows: without it the reader cannot tell where the
          // sampling draws end, and refuses the file.
          .and(
            check(
              lines.drop(lastRow + 1).nonEmpty && lines.drop(lastRow + 1).forall(_.startsWith("#")),
              "nothing follows the last row, so the draws cannot be counted"
            )
          )
          .and(
            check(
              lines.exists(_.contains("Elapsed Time")),
              "the trailing block is not the timing block"
            )
          )
          // And the configuration has to be one CmdStan could have written: a positive warmup
          // while the adaptation is engaged.
          .and(check(lines.exists(_.contains("num_samples = 10")), "the sample count is missing"))
          .and(
            check(
              lines.exists(_.contains("num_warmup = 10")),
              "the warmup count is zero, which the reader refuses while adaptation is engaged"
            )
          )
          .and(check(lines.exists(_.contains("engaged = true")), "the adaptation is not declared"))
  }

  pureTest("a file that is not a file says what is wrong with it") {
    val cases = List(
      ("nothing at all", Nil),
      ("comments only", List("# a", "# b")),
      ("an empty column name", List("lp__,,theta", "1,2,3")),
      ("a short row", List("lp__,theta", "1")),
      ("a long row", List("lp__,theta", "1,2,3")),
      ("a value that is not a number", List("lp__,theta", "1,banana")),
      ("two columns of one name", List("theta,theta", "1,2"))
    )
    forEach(cases) { (name, lines) =>
      StanCsv.parse(lines) match
        case Right(draws) => failure(s"$name was accepted as ${draws.names.toList}")
        case Left(error) =>
          check(
            error.message.nonEmpty && error.message.contains("stan.csv"),
            s"$name: the error does not name the source: ${error.message}"
          )
    }
  }

  pureTest("the non-finite values CmdStan writes are read back") {
    val lines = List("lp__,theta", "nan,inf", "-inf,1.5", "NaN,Infinity")
    StanCsv.parse(lines) match
      case Left(error) => failure(error.message)
      case Right(draws) =>
        val values = draws.chains(0)
        check(values(0)(0).isNaN, s"'nan' read as ${values(0)(0)}")
          .and(check(values(0)(1) == Double.PositiveInfinity, s"'inf' read as ${values(0)(1)}"))
          .and(check(values(1)(0) == Double.NegativeInfinity, s"'-inf' read as ${values(1)(0)}"))
          .and(check(values(2)(0).isNaN, s"'NaN' read as ${values(2)(0)}"))
          .and(
            check(values(2)(1) == Double.PositiveInfinity, s"'Infinity' read as ${values(2)(1)}")
          )
  }

  pureTest("a posteriordb reference summary parses, names and all") {
    bayes4s.testkit.Reference
      .text("posteriordb/eight_schools-eight_schools_noncentered/reference_summary.json")
      .left
      .map(_.message) match
      case Left(message) => failure(message)
      case Right(text) =>
        PosteriordbSummary.parse(text) match
          case Left(error) => failure(error.message)
          case Right(summary) =>
            val names = summary.rows.map(_.name)
            val mu = summary.rows.find(_.name == "mu")
            check(
              summary.posterior == "eight_schools-eight_schools_noncentered",
              s"the posterior is '${summary.posterior}'"
            )
              .and(check(names.contains("mu") && names.contains("tau"), s"the rows are $names"))
              .and(
                check(
                  names.exists(_.startsWith("theta[")),
                  s"no row for a theta: $names"
                )
              )
              .and(
                // posteriordb writes `theta[1]`, CmdStan writes `theta.1`, and a comparison has
                // to match them up.
                check(
                  summary.rows.exists(_.cmdStanName == "theta.1"),
                  s"no row converts to the CmdStan name: ${summary.rows.map(_.cmdStanName)}"
                )
              )
              .and(
                // posteriordb keeps R-hat per chain in `reference_info`, not per parameter: ten
                // chains of ten thousand draws, and every one of them converged.
                summary.diagnostics.fold(failure("the reference's own diagnostics are missing")) {
                  d =>
                    check(d.chains == 10, s"${d.chains} reference chains")
                      .and(check(d.drawsPerChain == 10_000, s"${d.drawsPerChain} draws a chain"))
                      .and(check(d.rhat.length == 10, s"${d.rhat.length} R-hats"))
                      .and(check(d.rhat.forall(_ < 1.01), s"a reference R-hat is ${d.rhat.max}"))
                      .and(
                        check(
                          d.divergentTransitions.forall(_ == 0.0),
                          s"the reference fit had divergent transitions: ${d.divergentTransitions}"
                        )
                      )
                }
              )
              .and(
                mu.fold(failure("no row for mu")) { row =>
                  check(row.sd > 0.0, s"its standard deviation is ${row.sd}")
                    .and(
                      check(row.q05 < row.q50 && row.q50 < row.q95, "its quantiles are unordered")
                    )
                    .and(check(row.essBulk > 1000.0, s"its effective size is ${row.essBulk}"))
                }
              )
  }
