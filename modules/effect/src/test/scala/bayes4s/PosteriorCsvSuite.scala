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
import fs2.io.file.Files
import fs2.io.file.Path
import weaver.SimpleIOSuite

/** CmdStan's CSV, off the disk and back.
  *
  * The codec is phase 5's and was checked there against `cmdstanpy.from_csv` and ArviZ; what this
  * suite checks is the part that is new — that the round trip through a real file is exact, that
  * the one-file-one-chain convention is enforced rather than silently broken, and that a fit of
  * four chains comes back as a fit of four chains.
  */
object PosteriorCsvSuite extends SimpleIOSuite with Approx:

  private val names = IArray("mu", "tau", "theta.1", "theta.2")
  private val chains = IArray.tabulate(3) { c =>
    IArray.tabulate(25)(i => IArray(0.1 * c + i, 1.0 + 0.01 * i, -i.toDouble, 2.0 * i))
  }
  private val draws = Draws.of(names, chains).toOption.get
  private val single = Draws.of(names, IArray(chains(0))).toOption.get

  private def temporary[A](use: Path => IO[A]): IO[A] =
    Files[IO].tempDirectory.use(use)

  test("a chain written and read back is the same chain, exactly") {
    temporary { directory =>
      val path = directory / "posterior.csv"
      for
        _ <- PosteriorCsv[IO].write(single, path)
        exists <- Files[IO].exists(path)
        back <- PosteriorCsv[IO].read(path)
      yield
        val left = single.flatten
        val right = back.flatten
        check(exists, "no file was written")
          .and(
            check(back.names.toList == names.toList, s"the names came back as ${back.names.toList}")
          )
          .and(check(back.chainCount == 1, s"${back.chainCount} chains"))
          .and(check(right.length == left.length, s"${right.length} rows against ${left.length}"))
          .and(
            check(
              (0 until left.length)
                .forall(i => (0 until left(i).length).forall(j => left(i)(j) == right(i)(j))),
              "a value changed in the round trip"
            )
          )
    }
  }

  test("a fit of several chains goes to one file each, and comes back as one fit") {
    temporary { directory =>
      for
        written <- PosteriorCsv[IO].writeAll(draws, directory, "fit")
        back <- PosteriorCsv[IO].readAll(written)
        listed <- Files[IO].list(directory).compile.toList
      yield
        val left = draws.flatten
        val right = back.flatten
        check(written.length == 3, s"${written.length} files written")
          .and(
            check(
              written.map(_.fileName.toString) == List("fit-1.csv", "fit-2.csv", "fit-3.csv"),
              s"the files are named ${written.map(_.fileName.toString)}"
            )
          )
          .and(check(listed.length == 3, s"${listed.length} files in the directory"))
          .and(check(back.chainCount == 3, s"${back.chainCount} chains came back"))
          .and(
            check(
              (0 until left.length)
                .forall(i => (0 until left(i).length).forall(j => left(i)(j) == right(i)(j))),
              "a value changed in the round trip"
            )
          )
    }
  }

  test("writing a fit to one file is refused, not silently truncated") {
    // The tempting failure: write chain 0 and drop the other three. One CmdStan file holds one
    // chain, so a fit needs `writeAll`, and asking for the wrong one has to say so.
    temporary { directory =>
      PosteriorCsv[IO].write(draws, directory / "all.csv").attempt.flatMap { attempted =>
        Files[IO].exists(directory / "all.csv").map { exists =>
          check(attempted.isLeft, "a three-chain fit was written to one file")
            .and(check(!exists, "a file was created before the refusal"))
            .and {
              attempted match
                case Left(error: BayesError) =>
                  check(
                    error.message.contains("writeAll"),
                    s"the error does not say what to use instead: ${error.message}"
                  )
                case Left(other) => failure(s"the error is not a BayesError: $other")
                case Right(_) => failure("a three-chain fit was written to one file")
            }
        }
      }
    }
  }

  test("a malformed file is an error of F, not a silent empty fit") {
    temporary { directory =>
      val path = directory / "rubbish.csv"
      for
        _ <- fs2.Stream
          .emit("this is not a CmdStan file\n")
          .through(Files[IO].writeUtf8(path))
          .compile
          .drain
        attempted <- PosteriorCsv[IO].read(path).attempt
        missing <- PosteriorCsv[IO].read(directory / "absent.csv").attempt
      yield check(attempted.isLeft, "a malformed file parsed into draws")
        .and(check(missing.isLeft, "a file that does not exist parsed into draws"))
        .and {
          attempted match
            case Left(_: BayesError) => success
            case Left(other) => failure(s"the parse error is not a BayesError: $other")
            case Right(_) => failure("a malformed file parsed into draws")
        }
    }
  }

  test("a fit goes from the sampler to a file and back without going through anything else") {
    // The end-to-end path the module exists for: sample in `IO`, write, read, summarise. Nothing
    // in between touches a `java.io` or a pure interpreter.
    val schools = EightSchools.Data
      .of(
        IArray(28.0, 8.0, -3.0, 7.0, -1.0, 1.0, 18.0, 12.0),
        IArray(15.0, 10.0, 16.0, 11.0, 9.0, 11.0, 10.0, 18.0)
      )
      .toOption
      .get
    val model = EightSchools.nonCentred(schools)
    val settings = RunSettings(chains = 2, warmup = 100, samples = 60)
    temporary { directory =>
      for
        fitted <- Sampler[IO].run(model, settings, Seed(13L))
        written <- PosteriorCsv[IO].writeAll(fitted, directory, "schools")
        back <- PosteriorCsv[IO].readAll(written)
      yield
        val left = fitted.flatten
        val right = back.flatten
        check(back.chainCount == 2, s"${back.chainCount} chains")
          .and(check(right.length == 120, s"${right.length} rows for two chains of sixty"))
          .and(
            check(
              (0 until left.length)
                .forall(i => (0 until left(i).length).forall(j => left(i)(j) == right(i)(j))),
              "the fit changed on its way through the disk"
            )
          )
    }
  }
