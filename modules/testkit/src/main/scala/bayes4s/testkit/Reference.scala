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

package bayes4s.testkit

import java.nio.charset.StandardCharsets

import scala.util.Try
import scala.util.Using
import scala.util.matching.Regex

import cats.syntax.all.*
import io.circe.Decoder
import io.circe.Json
import io.circe.parser

/** Loads the reference data from the test classpath.
  *
  * The files are produced by the scripts in `reference/`, and each one records its own
  * provenance: tool, version, repository, commit. They use Python's `Infinity`, `-Infinity` and
  * `NaN` tokens, which JSON does not define: [[json]] quotes them before parsing, leaving
  * anything inside a string literal untouched, and [[looseDouble]] reads them back as the
  * matching IEEE values.
  *
  * No function here throws: a file that is absent, unreadable or malformed is a `Left`, so a
  * suite fails with a message that names the file.
  */
object Reference:

  /** Where the data sits on the classpath. */
  val root: String = "reference/"

  enum Error:
    case NotFound(path: String)
    case Unreadable(path: String, detail: String)
    case Malformed(path: String, detail: String)
    case MissingColumn(path: String, name: String)
    case NotANumber(path: String, where: String, raw: String)

    def message: String = this match
      case NotFound(p) => s"reference file not on the classpath: $root$p"
      case Unreadable(p, d) => s"cannot read $root$p: $d"
      case Malformed(p, d) => s"$root$p is malformed: $d"
      case MissingColumn(p, n) => s"$root$p has no column named '$n'"
      case NotANumber(p, w, r) => s"$root$p, $w: '$r' is not a number"

  /** Raw contents of a reference file. */
  def text(path: String): Either[Error, String] =
    Option(getClass.getClassLoader.getResourceAsStream(root + path)) match
      case None => Left(Error.NotFound(path))
      case Some(stream) =>
        Using(stream)(s => new String(s.readAllBytes(), StandardCharsets.UTF_8)).toEither.leftMap:
          t => Error.Unreadable(path, Option(t.getMessage).getOrElse(t.toString))

  /** A reference file as JSON, Python's non-finite tokens included. */
  def json(path: String): Either[Error, Json] =
    text(path).flatMap: raw =>
      parser.parse(quoteNonFinite(raw)).leftMap(f => Error.Malformed(path, f.message))

  /** A `Double` decoder that accepts either a JSON number or one of the `"NaN"`, `"Infinity"`
    * and `"-Infinity"` tokens that [[json]] produces.
    */
  val looseDouble: Decoder[Double] =
    Decoder.decodeDouble.or(Decoder.decodeString.emap {
      case "NaN" => Right(Double.NaN)
      case "Infinity" => Right(Double.PositiveInfinity)
      case "-Infinity" => Right(Double.NegativeInfinity)
      case other => Left(s"'$other' is neither a number nor an IEEE special value")
    })

  /** A vector of doubles, each read with [[looseDouble]]. */
  val doubleArray: Decoder[IArray[Double]] =
    Decoder.decodeList(looseDouble).map(xs => IArray.unsafeFromArray(xs.toArray))

  /** Named columns of a CmdStan CSV: `#` comment lines are skipped and the first remaining line
    * carries the names.
    */
  def csvColumns(path: String, columns: List[String]): Either[Error, Map[String, IArray[Double]]] =
    text(path).flatMap: raw =>
      val rows = dataRows(raw)
      rows.headOption
        .toRight(Error.Malformed(path, "no row outside the '#' comments"))
        .flatMap: header =>
          val index = header.split(",", -1).toVector.map(unquote).zipWithIndex.toMap
          columns.traverse(column => column1(path, rows.tail, index, column)).map(_.toMap)

  /** Values of a single-column CSV with no header line, such as `ar1.csv`. */
  def csvValues(path: String): Either[Error, IArray[Double]] =
    text(path).flatMap: raw =>
      dataRows(raw).zipWithIndex
        .traverse((line, i) => parseDouble(path, s"line ${i + 1}", line))
        .map(unsafeToIArray)

  /** The 32 JSON reference files. */
  val jsonFiles: List[String] = List(
    "multilevel/multilevel.json",
    "posteriordb-like/fictional-varying_intercept_slope_correlated/data.json",
    "posteriordb-like/fictional-varying_intercept_slope_correlated/reference_summary.json",
    "posteriordb/arK-arK/data.json",
    "posteriordb/arK-arK/reference_summary.json",
    "posteriordb/arma-arma11/data.json",
    "posteriordb/arma-arma11/reference_summary.json",
    "posteriordb/dogs-dogs/data.json",
    "posteriordb/dogs-dogs/reference_summary.json",
    "posteriordb/earnings-logearn_height/data.json",
    "posteriordb/earnings-logearn_height/reference_summary.json",
    "posteriordb/eight_schools-eight_schools_centered/data.json",
    "posteriordb/eight_schools-eight_schools_noncentered/data.json",
    "posteriordb/eight_schools-eight_schools_noncentered/reference_summary.json",
    "posteriordb/garch-garch11/data.json",
    "posteriordb/garch-garch11/reference_summary.json",
    "posteriordb/gp_pois_regr-gp_pois_regr/data.json",
    "posteriordb/gp_pois_regr-gp_pois_regr/reference_summary.json",
    "posteriordb/index.json",
    "posteriordb/kidiq-kidscore_momiq/data.json",
    "posteriordb/kidiq-kidscore_momiq/reference_summary.json",
    "posteriordb/low_dim_gauss_mix-low_dim_gauss_mix/data.json",
    "posteriordb/low_dim_gauss_mix-low_dim_gauss_mix/reference_summary.json",
    "python/densities_extreme.json",
    "python/scoring.json",
    "python/special_functions.json",
    "python/worked_examples.json",
    "rethinking/rethinking.json",
    "stan-math/distributions.json",
    "stan-math/transforms.json",
    "stan/diagnostics/expected.json",
    "stan/hmc/hmc.json"
  )

  /** The 6 CSV reference files. */
  val csvFiles: List[String] = List(
    "stan/diagnostics/ar1.csv",
    "stan/diagnostics/bern1.csv",
    "stan/diagnostics/bern2.csv",
    "stan/diagnostics/bern3.csv",
    "stan/diagnostics/bern4.csv",
    "stan/diagnostics/cmdstan-2-35-0-stansummary.csv"
  )

  /** The 12 remaining files: the posteriordb and fictional Stan models, and one note. */
  val otherFiles: List[String] = List(
    "posteriordb-like/fictional-varying_intercept_slope_correlated/model.stan",
    "posteriordb/arK-arK/model.stan",
    "posteriordb/arma-arma11/model.stan",
    "posteriordb/dogs-dogs/model.stan",
    "posteriordb/earnings-logearn_height/model.stan",
    "posteriordb/eight_schools-eight_schools_centered/README.md",
    "posteriordb/eight_schools-eight_schools_centered/model.stan",
    "posteriordb/eight_schools-eight_schools_noncentered/model.stan",
    "posteriordb/garch-garch11/model.stan",
    "posteriordb/gp_pois_regr-gp_pois_regr/model.stan",
    "posteriordb/kidiq-kidscore_momiq/model.stan",
    "posteriordb/low_dim_gauss_mix-low_dim_gauss_mix/model.stan"
  )

  /** Every reference file the repository is expected to carry. */
  val allFiles: List[String] = jsonFiles ++ csvFiles ++ otherFiles

  // --- internals --------------------------------------------------------------------------

  private val stringLiteral: Regex = """"(?:[^"\\]|\\.)*"""".r
  private val nonFinite: Regex = """(?<![A-Za-z0-9_.$])(-?Infinity|NaN)(?![A-Za-z0-9_.$])""".r

  /** Quotes Python's non-finite tokens **outside** JSON string literals. A string such as
    * `"every diagnostic must return NaN"` must come through untouched.
    */
  private def quoteNonFinite(raw: String): String =
    val (chunks, from) =
      stringLiteral
        .findAllMatchIn(raw)
        .foldLeft((Vector.empty[String], 0)): (acc, m) =>
          val (done, start) = acc
          (done :+ quoteOutsideString(raw.substring(start, m.start)) :+ m.matched, m.end)
    (chunks :+ quoteOutsideString(raw.substring(from))).mkString

  private def quoteOutsideString(segment: String): String =
    nonFinite.replaceAllIn(segment, m => "\"" + m.group(1) + "\"")

  /** One column and its values. The return type is written out on purpose: without it,
    * `traverse` cannot choose between the `Either` and the `Id` applicative.
    */
  private def column1(
      path: String,
      rows: Vector[String],
      index: Map[String, Int],
      column: String
  ): Either[Error, (String, IArray[Double])] =
    index
      .get(column)
      .toRight(Error.MissingColumn(path, column))
      .flatMap(i => cells(path, rows, column, i).map(column -> _))

  private def dataRows(raw: String): Vector[String] =
    raw.linesIterator.map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).toVector

  private def cells(
      path: String,
      rows: Vector[String],
      column: String,
      i: Int
  ): Either[Error, IArray[Double]] =
    rows.zipWithIndex
      .traverse: (line, r) =>
        val split = line.split(",", -1)
        if i >= split.length then
          Left(Error.Malformed(path, s"row ${r + 1} has ${split.length} cells, '$column' is at $i"))
        else parseDouble(path, s"row ${r + 1}, column '$column'", unquote(split(i)))
      .map(unsafeToIArray)

  private def parseDouble(path: String, where: String, raw: String): Either[Error, Double] =
    Try(java.lang.Double.parseDouble(raw.trim)).toEither.leftMap(_ =>
      Error.NotANumber(path, where, raw)
    )

  private def unquote(s: String): String =
    val t = s.trim
    if t.length >= 2 && t.startsWith("\"") && t.endsWith("\"") then t.substring(1, t.length - 1)
    else t

  /** `toArray` allocates a fresh array that nobody else holds, so wrapping it as an `IArray`
    * cannot create a mutable alias.
    */
  private def unsafeToIArray(xs: Vector[Double]): IArray[Double] =
    IArray.unsafeFromArray(xs.toArray)
