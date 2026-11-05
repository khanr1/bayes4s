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

import io.circe.Decoder
import io.circe.parser

/** One parameter's row of a posteriordb reference summary.
  *
  * The names are posteriordb's — `mu[1]`, not CmdStan's `mu.1` — because that is what the files
  * carry; `cmdStanName` converts, since a comparison against a fit of this library's has to match
  * them up.
  *
  * There is no R-hat here, and that is the file's shape rather than an omission: posteriordb keeps
  * the per-parameter moments and effective sizes in `parameters` and the convergence diagnostics
  * **per chain** in `reference_info`, where [[ReferenceDiagnostics]] reads them.
  */
final case class ReferenceRow(
    name: String,
    mean: Double,
    sd: Double,
    q05: Double,
    q50: Double,
    q95: Double,
    essBulk: Double,
    mcseMean: Double,
    mcseSd: Double
):
  /** `mu[1]` as `mu.1`, and `L[1,2]` as `L.1.2`. */
  def cmdStanName: String =
    name.replace("[", ".").replace(",", ".").replace("]", "")

/** What posteriordb says about the reference fit itself: how it was run, and one value per chain
  * for each convergence diagnostic.
  */
final case class ReferenceDiagnostics(
    chains: Int,
    drawsPerChain: Int,
    rhat: List[Double],
    essBulk: List[Double],
    essTail: List[Double],
    divergentTransitions: List[Double]
)

/** A posteriordb reference summary: the posterior's name, why it is in the collection, a row per
  * parameter, and what the reference fit's own diagnostics were.
  */
final case class ReferenceSummary(
    posterior: String,
    why: Option[String],
    rows: List[ReferenceRow],
    diagnostics: Option[ReferenceDiagnostics]
)

/** Reading posteriordb's reference summaries, on the same principle as the CSV: a pure function of
  * text, with the file handling left to `effect`.
  *
  * These are the numbers phase 6 compares a fit against, and they are Monte Carlo estimates
  * themselves — ten chains of ten thousand draws — so a comparison against them is a comparison
  * within two Monte Carlo errors, never an equality.
  */
object PosteriordbSummary:

  def parse(text: String): Either[BayesError.Malformed, ReferenceSummary] =
    parser
      .parse(text)
      .left
      .map(failure => BayesError.Malformed("posteriordb summary", failure.getMessage))
      .flatMap { json =>
        val cursor = json.hcursor
        val read = for
          posterior <- cursor.get[String]("posterior")
          why <- cursor.get[Option[String]]("why")
          parameters <- cursor.get[Map[String, ReferenceRow]]("parameters")(using
            Decoder.decodeMap(using io.circe.KeyDecoder.decodeKeyString, rowDecoder)
          )
        yield ReferenceSummary(
          posterior,
          why,
          parameters.toList.sortBy((name, _) => name).map((name, row) => row.copy(name = name)),
          diagnostics(json)
        )
        read.left.map(failure => BayesError.Malformed("posteriordb summary", failure.getMessage))
      }

  private val rowDecoder: Decoder[ReferenceRow] = Decoder.instance { c =>
    for
      mean <- c.get[Double]("mean")
      sd <- c.get[Double]("sd")
      q05 <- c.get[Double]("q05")
      q50 <- c.get[Double]("q50")
      q95 <- c.get[Double]("q95")
      essBulk <- c.get[Double]("ess_bulk")
      mcseMean <- c.get[Double]("mcse_mean")
      mcseSd <- c.get[Double]("mcse_sd")
    yield ReferenceRow("", mean, sd, q05, q50, q95, essBulk, mcseMean, mcseSd)
  }

  /** The reference fit's own diagnostics, where the file keeps them: absent rather than an error
    * if a particular summary does not carry them.
    */
  private def diagnostics(json: io.circe.Json): Option[ReferenceDiagnostics] =
    val cursor = json.hcursor.downField("reference_info").downField("diagnostics")
    val read = for
      chains <- cursor.get[Int]("nchains")
      draws <- cursor.get[Int]("ndraws")
      rhat <- cursor.get[List[Double]]("r_hat")
      bulk <- cursor.get[List[Double]]("effective_sample_size_bulk")
      tail <- cursor.get[List[Double]]("effective_sample_size_tail")
      divergent <- cursor.get[List[Double]]("divergent_transitions")
    yield ReferenceDiagnostics(chains, draws, rhat, bulk, tail, divergent)
    read.toOption
