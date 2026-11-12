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

import cats.MonadThrow
import cats.effect.Concurrent
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Files
import fs2.io.file.Path
import fs2.text

/** Reading and writing CmdStan's CSV, as a stream.
  *
  * The codec is phase 5's and is not touched here: `StanCsv.render` and `StanCsv.parse` are pure,
  * already checked against `cmdstanpy.from_csv` and ArviZ, and this module only moves their lines
  * on and off a disk. Files go through `fs2.io.file.Files` and nothing in this module opens a
  * `java.io` anything.
  *
  * **One file holds one chain**, which is CmdStan's convention and phase 5's: `StanCsv.parseAll`
  * combines several files into one set of draws. So [[write]] refuses draws of more than one chain
  * rather than quietly writing the first and dropping the rest, and [[writeAll]] is the one to
  * reach for after a four-chain fit.
  * **Example.** CmdStan's CSV, on and off the disk. One file holds one chain, which is CmdStan's
  * convention and this library's:
  *
  * ```scala
  * //{
  * import bayes4s.syntax.{*, given}
  * import cats.effect.IO
  * import fs2.io.file.Path
  * final case class Mean[A](mu: A, sigma: A)
  * val observed = IArray(4.1, 5.2, 3.8, 4.9, 5.5, 4.4)
  * val model: Model[Mean] = new Model[Mean]:
  *   def params: ParamSpec[Mean] =
  *     (ParamSpec.real("mu"), ParamSpec.positive("sigma"))
  *       .mapN[Mean]([A] => (mu: A, sigma: A) => Mean(mu, sigma))
  *   def logPrior[A: Real](p: Mean[A]): A =
  *     LogDensity.normal(p.mu, 0.0.lift[A], 10.0.lift[A]) +
  *       LogDensity.halfNormal(p.sigma, 5.0.lift[A])
  *   def logLikelihood[A: Real](p: Mean[A]): A =
  *     Real[A].normalSum(observed, Real[A].filled(observed.length)(p.mu), p.sigma)
  * //}
  * val roundTrip: IO[Boolean] = fs2.io.file.Files[IO].tempDirectory.use { directory =>
  *   for
  *     fitted <- Sampler[IO].run(model, RunSettings(chains = 3), Seed(7L))
  *     written <- PosteriorCsv[IO].writeAll(fitted, directory, "fit")
  *     back <- PosteriorCsv[IO].readAll(written)
  *   yield back.chainCount == fitted.chainCount
  * }
  * ```
  *
  * Asking to write a whole fit into a single file is refused rather than quietly truncated to its
  * first chain.
  */
trait PosteriorCsv[F[_]]:

  /** One chain to one file. Refused, with a named error, for draws of several chains. */
  def write(draws: Draws, path: Path): F[Unit]

  /** One file, one chain. */
  def read(path: Path): F[Draws]

  /** Every chain to its own file, `<stem>-<chain>.csv`, as CmdStan names them. */
  def writeAll(draws: Draws, directory: Path, stem: String): F[List[Path]]

  /** Several files as one set of draws, in the order given. */
  def readAll(paths: List[Path]): F[Draws]

object PosteriorCsv:

  def apply[F[_]: Files: Concurrent]: PosteriorCsv[F] = new PosteriorCsv[F]:

    def write(draws: Draws, path: Path): F[Unit] =
      if draws.chainCount != 1 then
        MonadThrow[F].raiseError(
          BayesError.InvalidArgument(
            "PosteriorCsv.write",
            "draws.chainCount",
            draws.chainCount.toDouble,
            "exactly one chain: one CmdStan file holds one chain, so use writeAll for a fit"
          )
        )
      else lines(StanCsv.render(draws, chain = 0), path)

    def read(path: Path): F[Draws] =
      readLines(path).flatMap(read => MonadThrow[F].fromEither(StanCsv.parse(read)))

    def writeAll(draws: Draws, directory: Path, stem: String): F[List[Path]] =
      (0 until draws.chainCount).toList.traverse { c =>
        val path = directory / s"$stem-${c + 1}.csv"
        lines(StanCsv.render(draws, chain = c), path).as(path)
      }

    def readAll(paths: List[Path]): F[Draws] =
      paths
        .traverse(readLines)
        .flatMap(files => MonadThrow[F].fromEither(StanCsv.parseAll(files)))

    /** The lines of a file, streamed: a fit of ten thousand draws is a large file and is never
      * held twice.
      */
    private def readLines(path: Path): F[List[String]] =
      Files[F].readAll(path).through(text.utf8.decode).through(text.lines).compile.toList

    private def lines(rendered: List[String], path: Path): F[Unit] =
      Stream
        .emits(rendered)
        .intersperse("\n")
        .append(Stream.emit("\n"))
        .through(text.utf8.encode)
        .through(Files[F].writeAll(path))
        .compile
        .drain
