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

/** CmdStan's CSV, parsed and rendered as pure functions of lines.
  *
  * No file is touched here. The `effect` module connects fs2 to these two functions and does
  * nothing else, which is what keeps the format's rules — where the comments may appear, what the
  * header means — in one testable place.
  *
  * The format, as CmdStan writes it: a block of `#` comments carrying the configuration, then the
  * header line of column names, then **more** `#` comments (the adaptation's step size and mass
  * matrix), then the rows, then possibly more comments with the timing. So a comment can appear
  * anywhere and the header is simply the first line that is not one.
  */
object StanCsv:

  /** One file, which in CmdStan is one chain. */
  def parse(lines: List[String]): Either[BayesError.Malformed, Draws] =
    val rows = lines.map(_.trim).filter(line => line.nonEmpty && !line.startsWith("#"))
    rows match
      case Nil => Left(BayesError.Malformed("stan.csv", "no line outside the '#' comments"))
      case header :: body =>
        val names = header.split(",", -1).toList.map(_.trim)
        if names.exists(_.isEmpty) then
          Left(BayesError.Malformed("stan.csv", s"the header has an empty name: '$header'"))
        else if body.isEmpty then
          // A header and nothing else is not an empty fit, it is a truncated file or a mistyped
          // path. Accepting it returns a posterior of no draws, from which every statistic comes
          // back `NaN` with nothing to say why — found while writing the effectful reader
          // (docs/design.md).
          Left(
            BayesError.Malformed(
              "stan.csv",
              s"a header and no draws: '${header.take(60)}'"
            )
          )
        else
          body.zipWithIndex
            .foldLeft[Either[BayesError.Malformed, Vector[IArray[Double]]]](Right(Vector.empty)) {
              case (collected, (line, i)) =>
                collected.flatMap(draws => row(names.length, line, i).map(draws :+ _))
            }
            .flatMap { draws =>
              Draws
                .single(IArray.from(names), IArray.from(draws))
                .left
                .map(error => BayesError.Malformed("stan.csv", error.message))
            }

  /** Several files, which in CmdStan is several chains of one fit.
    *
    * The names and the number of draws have to agree across them, and saying so here is the point:
    * a fit assembled from files with different columns would summarise something that is not a
    * posterior.
    */
  def parseAll(files: List[List[String]]): Either[BayesError.Malformed, Draws] =
    files
      .foldLeft[Either[BayesError.Malformed, Vector[Draws]]](Right(Vector.empty)) {
        (collected, lines) => collected.flatMap(parsed => parse(lines).map(parsed :+ _))
      }
      .flatMap { parsed =>
        parsed.headOption match
          case None => Left(BayesError.Malformed("stan.csv", "no file was given"))
          case Some(first) =>
            val disagreeing = parsed.zipWithIndex.collectFirst {
              case (draws, i) if draws.names.toList != first.names.toList =>
                BayesError.Malformed(
                  s"stan.csv, file $i",
                  s"its columns are ${draws.names.toList.mkString(",")}, not " +
                    first.names.toList.mkString(",")
                )
              case (draws, i) if draws.iterations != first.iterations =>
                BayesError.Malformed(
                  s"stan.csv, file $i",
                  s"it has ${draws.iterations} draws, not ${first.iterations}"
                )
            }
            disagreeing.toLeft(()).flatMap { _ =>
              Draws
                .of(first.names, IArray.from(parsed.toList.map(_.chains(0))))
                .left
                .map(error => BayesError.Malformed("stan.csv", error.message))
            }
      }

  /** One chain, as a CmdStan file.
    *
    * The shape is not decoration. CmdStan's own reader finds the draws by looking for four blocks
    * in order — a header line **beginning with `lp__`**, a `#` line after it, the rows, and a `#`
    * block after those — and a file missing any of them is refused outright. The first version of
    * this renderer left out the trailing timing block and `cmdstanpy.from_csv` rejected the file
    * with "Unable to count warmup and sampling draws"; docs/validation.md records it.
    *
    * Everything a `Draws` does not hold — the real step size, the mass matrix, the elapsed time —
    * is written as the placeholder CmdStan's format needs rather than invented as a plausible
    * number.
    */
  def render(draws: Draws, chain: Int = 0): List[String] =
    if chain < 0 || chain >= draws.chainCount then
      List(s"# no chain $chain", draws.names.toList.mkString(","))
    else
      val configuration = List(
        "# stan_version_major = 2",
        "# stan_version_minor = 35",
        "# stan_version_patch = 0",
        "# model = bayes4s",
        "# method = sample",
        "#   sample",
        s"#     num_samples = ${draws.iterations}",
        // The warmup count has to be positive while the adaptation is engaged, which is what
        // CmdStan's own reader checks; the draws in the body are the kept ones either way.
        s"#     num_warmup = ${math.max(draws.iterations, 1)}",
        "#     save_warmup = false",
        "#     thin = 1",
        "#     adapt",
        "#       engaged = true",
        "#     algorithm = hmc",
        "#       hmc",
        "#         engine = nuts",
        "#           nuts",
        "#             max_depth = 10",
        "#         metric = diag_e",
        s"# id = ${chain + 1}",
        "# output",
        "#   file = bayes4s.csv"
      )
      val header = draws.names.toList.mkString(",")
      val rows = (0 until draws.iterations).toList.map { i =>
        val values = draws.chains(chain)(i)
        (0 until values.length).map(j => cell(values(j))).mkString(",")
      }
      // CmdStan's reader expects the adaptation block to carry a step size and a mass matrix with
      // one entry per unconstrained parameter. A `Draws` holds neither, so both are placeholders:
      // a step size of 1 and a unit diagonal over the columns that are not the sampler's own.
      val parameters = draws.names.toList.count(!_.endsWith("__"))
      val adaptation = List(
        "# Adaptation terminated",
        "# Step size = 1",
        "# Diagonal elements of inverse mass matrix:",
        List.fill(math.max(parameters, 1))("1").mkString(", ")
      ).zipWithIndex.map((line, i) => if i == 3 then s"# $line" else line)
      val timing = List(
        "# ",
        "#  Elapsed Time: 0 seconds (Warm-up)",
        "#                0 seconds (Sampling)",
        "#                0 seconds (Total)",
        "#"
      )
      configuration ++ (header :: adaptation) ++ rows ++ timing

  /** One file per chain, which is how CmdStan writes a fit of several chains. */
  def renderAll(draws: Draws): List[List[String]] =
    (0 until draws.chainCount).toList.map(chain => render(draws, chain))

  /** A number as CmdStan writes it, and as this library can read back exactly.
    *
    * `%.17g` would round-trip too and makes every file a third larger; the shortest
    * representation that reads back as the same double is what `Double.toString` gives, and
    * CmdStan's own files are read by the same parser either way. Non-finite values are written as
    * CmdStan writes them, because a divergent transition can produce one.
    */
  private def cell(x: Double): String =
    if x.isNaN then "nan"
    else if x == Double.PositiveInfinity then "inf"
    else if x == Double.NegativeInfinity then "-inf"
    else x.toString

  private def row(
      width: Int,
      line: String,
      index: Int
  ): Either[BayesError.Malformed, IArray[Double]] =
    val cells = line.split(",", -1).toList.map(_.trim)
    if cells.length != width then
      Left(
        BayesError.Malformed(
          "stan.csv",
          s"row ${index + 1} has ${cells.length} values, the header has $width"
        )
      )
    else
      cells
        .foldLeft[Either[BayesError.Malformed, Vector[Double]]](Right(Vector.empty)) {
          (collected, cell) =>
            collected.flatMap(values => number(cell, index).map(values :+ _))
        }
        .map(IArray.from)

  private def number(cell: String, index: Int): Either[BayesError.Malformed, Double] =
    cell.toLowerCase match
      case "nan" => Right(Double.NaN)
      case "inf" | "+inf" | "infinity" => Right(Double.PositiveInfinity)
      case "-inf" | "-infinity" => Right(Double.NegativeInfinity)
      case _ =>
        cell.toDoubleOption.toRight(
          BayesError.Malformed("stan.csv", s"row ${index + 1}: '$cell' is not a number")
        )
