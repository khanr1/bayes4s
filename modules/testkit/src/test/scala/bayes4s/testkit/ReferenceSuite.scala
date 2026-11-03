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

import weaver.SimpleIOSuite

/** Phase 0 criterion: every reference file is on the test classpath and loads, Python's
  * non-finite tokens included.
  *
  * Every value asserted here comes from the files themselves (Stan Math, mpmath, CmdStan 2.35.0).
  * None of them comes from running this library.
  */
object ReferenceSuite extends SimpleIOSuite with Approx:

  pureTest("the 45 reference files of plan section 7 are on the test classpath") {
    val missing = Reference.allFiles.filter(p => Reference.text(p).isLeft)
    check(
      Reference.allFiles.size == 45,
      s"the inventory lists ${Reference.allFiles.size} files, expected 45"
    ).and(check(missing.isEmpty, s"missing from the classpath: ${missing.mkString(", ")}"))
      .and(
        check(
          Reference.allFiles.distinct.size == Reference.allFiles.size,
          "the inventory lists the same file twice"
        )
      )
  }

  pureTest("every JSON reference file parses") {
    val failures = Reference.jsonFiles.flatMap { p =>
      Reference.json(p).left.toOption.map(e => e.message)
    }
    check(failures.isEmpty, failures.mkString(" | "))
  }

  pureTest("every CSV reference file parses") {
    val stanCsv = Reference.csvFiles.filter(_.contains("bern"))
    val failures = stanCsv.flatMap { p =>
      Reference.csvColumns(p, List("lp__", "theta")).left.toOption.map(_.message)
    } ++ Reference.csvValues("stan/diagnostics/ar1.csv").left.toOption.map(_.message)
    check(failures.isEmpty, failures.mkString(" | "))
  }

  pureTest("Python's unquoted -Infinity decodes to the IEEE value") {
    // densities_extreme.json, case 9: poisson_lpmf(5 | 0) = -Inf, the out-of-domain contract.
    val decoded =
      for
        parsed <- Reference.json("python/densities_extreme.json").left.map(_.message)
        entry = parsed.hcursor.downField("cases").downN(9)
        name <- entry.downField("distribution").as[String].left.map(_.getMessage)
        value <- entry.downField("value").as(using Reference.looseDouble).left.map(_.getMessage)
      yield (name, value)
    decoded match
      case Left(message) => failure(message)
      case Right((name, value)) =>
        check(name == "poisson_lpmf", s"case 9 is '$name', expected poisson_lpmf")
          .and(check(value == Double.NegativeInfinity, s"value decoded as $value, expected -Inf"))
  }

  pureTest("an unquoted Infinity in a Stan Math invalid case decodes to +Inf") {
    val decoded = Reference
      .json("stan-math/distributions.json")
      .left
      .map(_.message)
      .flatMap { j =>
        j.hcursor
          .downField("distributions")
          .downField("normal")
          .downField("lpdf")
          .downField("invalid")
          .downN(0)
          .downField("value")
          .as(using Reference.looseDouble)
          .left
          .map(_.getMessage)
      }
    check(decoded == Right(Double.PositiveInfinity), s"decoded $decoded, expected Right(Infinity)")
  }

  pureTest("the word NaN inside a JSON string is left untouched") {
    // The trap in quoting the tokens: expected.json describes in prose which columns must make
    // the diagnostics return NaN.  Quoting that NaN would break the surrounding string.
    val decoded = Reference
      .json("stan/diagnostics/expected.json")
      .left
      .map(_.message)
      .flatMap(_.hcursor.downField("columns").downField("constant").as[String].left.map(_.getMessage))
    check(
      decoded == Right("column 'divergent__' (all zero): every diagnostic must return NaN"),
      s"columns.constant decoded as $decoded"
    )
  }

  pureTest("the CmdStan CSV columns carry the values CmdStan wrote") {
    val loaded = Reference.csvColumns("stan/diagnostics/bern1.csv", List("lp__", "theta"))
    loaded match
      case Left(e) => failure(e.message)
      case Right(columns) =>
        val lp = columns("lp__")
        val theta = columns("theta")
        check(lp.length == 1000, s"lp__ has ${lp.length} draws, expected 1000")
          .and(check(theta.length == 1000, s"theta has ${theta.length} draws, expected 1000"))
          .and(close(lp(0), -6.80778, 0.0, "first lp__ of bern1.csv"))
          .and(close(theta(0), 0.208532, 0.0, "first theta of bern1.csv"))
  }

  pureTest("the headerless AR(1) series loads") {
    Reference.csvValues("stan/diagnostics/ar1.csv") match
      case Left(e) => failure(e.message)
      case Right(xs) =>
        check(xs.length == 1000, s"ar1.csv has ${xs.length} values, expected 1000")
          .and(close(xs(0), 0.747858687681513, 0.0, "first value of ar1.csv"))
  }

  pureTest("a missing file and a missing column are values, not exceptions") {
    val absent = Reference.json("python/does_not_exist.json")
    val noColumn = Reference.csvColumns("stan/diagnostics/bern1.csv", List("not_a_column"))
    check(
      absent == Left(Reference.Error.NotFound("python/does_not_exist.json")),
      s"a missing file gave $absent"
    ).and(
      check(
        noColumn == Left(
          Reference.Error.MissingColumn("stan/diagnostics/bern1.csv", "not_a_column")
        ),
        s"a missing column gave $noColumn"
      )
    )
  }
