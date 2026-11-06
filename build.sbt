// Dependency versions were checked on Maven Central on 3 November 2026 (docs/design.md, D-2).
// Scala 3.3.x really is the LTS line: 3.3.9 shipped on 22 October 2026, while the 3.7.x line
// stopped in November 2025.
val Scala3Lts = "3.3.9"

val catsV = "2.13.0"
val catsEffectV = "3.7.1"
val fs2V = "3.14.0"
val fs2DataV = "1.14.1"
val circeV = "0.14.17"
val commonsNumbersV = "1.3"
val weaverV = "0.13.0"

ThisBuild / tlBaseVersion := "0.1"
ThisBuild / organization := "io.github.bayes4s"
ThisBuild / organizationName := "bayes4s contributors"
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := Nil

ThisBuild / scalaVersion := Scala3Lts
ThisBuild / crossScalaVersions := Seq(Scala3Lts)
ThisBuild / tlJdkRelease := Some(17)
// scalafmt 3.11.5 needs a JDK >= 23 (docs/design.md, D-3); 25 is the current LTS.
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("25"))

// sbt-typelevel still pins ubuntu-22.04, an image GitHub has deprecated. `ubuntu-latest` is
// correct whether or not that image is still served.
ThisBuild / githubWorkflowOSes := Seq("ubuntu-latest")

// The dependency-submission job calls an API that needs `contents: write`, which a repository's
// GITHUB_TOKEN does not have by default: it would fail on every push to the default branch. It
// buys nothing until Dependabot is configured, so it is off (docs/design.md, D-15).
ThisBuild / tlCiDependencyGraphJob := false

// Publishing is off (docs/design.md, D-1): no release branch and no release tag, so ci.yml
// carries no publish job at all. Turning it on is three settings and one regeneration.
// An empty set of publish target branches is what actually removes the job; sbt-typelevel's
// tlCiReleaseBranches and tlCiReleaseTags feed into it, so setting them too would be dead
// configuration. To publish: set `tlCiReleaseBranches := Seq("main")`, drop this line, and
// regenerate the workflows.
ThisBuild / githubWorkflowPublishTargetBranches := Seq()
ThisBuild / tlCiHeaderCheck := true
ThisBuild / tlCiScalafmtCheck := true
ThisBuild / tlCiScalafixCheck := true

ThisBuild / semanticdbEnabled := true

// CI refuses a private term in the tree and in the commit messages of a pull request
// (docs/design.md, D-7). The HMAC key comes from a repository secret; without it the script
// fails instead of passing in silence.
ThisBuild / githubWorkflowEnv += "BAYES4S_PRIVATE_TERMS_KEY" -> "${{ secrets.BAYES4S_PRIVATE_TERMS_KEY }}"
ThisBuild / githubWorkflowBuildPreamble ++= Seq(
  WorkflowStep.Run(
    commands = List("python3 scripts/check-private-terms.py"),
    name = Some("Refuse private terms in the tree")
  ),
  WorkflowStep.Run(
    commands = List(
      "python3 scripts/check-private-terms.py --commits ${{ github.event.pull_request.base.sha }}..${{ github.sha }}"
    ),
    name = Some("Refuse private terms in the commit messages"),
    cond = Some("github.event_name == 'pull_request'")
  ),
  // The annotated sites of encapsulated local mutation must match the register they are argued
  // for in (docs/design.md, D-13).
  WorkflowStep.Run(
    commands = List("python3 scripts/check-mutation-register.py"),
    name = Some("Check the register of local mutation")
  ),
  // The manual's prose keeps the vocabulary of the glossary (docs/manual/glossary.md).
  WorkflowStep.Run(
    commands = List("python3 scripts/check-terminology.py"),
    name = Some("Check the manual's vocabulary")
  ),
  // Appendix A's table of Stan names points at things that exist.
  WorkflowStep.Run(
    commands = List("python3 scripts/check-stan-names.py"),
    name = Some("Check the table of Stan names")
  )
)

// Every code block in the manual is compiled against the current library.
ThisBuild / githubWorkflowBuildPostamble += WorkflowStep.Sbt(
  List("docs/mdoc"),
  name = Some("Compile the manual")
)

// The slow statistical suites (SBC, posteriordb) live in their own src/slow/scala, outside
// `sbt test`. `sbt slow:test` runs them; `-Dbayes4s.stress=true` asks them for fresh seeds and
// more draws.
lazy val Slow = config("slow").extend(Test)

lazy val weaverFramework = new TestFramework("weaver.framework.CatsEffect")

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "weaver-cats" % weaverV % Test,
    "org.typelevel" %% "weaver-scalacheck" % weaverV % Test,
    "org.typelevel" %% "weaver-discipline" % weaverV % Test,
    "org.typelevel" %% "cats-laws" % catsV % Test
  ),
  Test / testFrameworks += weaverFramework,
  Slow / testFrameworks += weaverFramework
)

def mod(dir: String): Project =
  Project(dir, file(s"modules/$dir"))
    .settings(name := s"bayes4s-$dir", moduleName := s"bayes4s-$dir")
    .configs(Slow)
    .settings(inConfig(Slow)(Defaults.testSettings))
    .settings(commonSettings)

lazy val root = project
  .in(file("."))
  .enablePlugins(NoPublishPlugin)
  .aggregate(
    core,
    ad,
    distributions,
    transforms,
    inference,
    diagnostics,
    predictive,
    scoring,
    nowcast,
    stanIo,
    effect,
    testkit,
    benchmarks,
    examples,
    docs
  )

// --- pure core: no cats-effect, no I/O, no clock, no global randomness -------------------

lazy val core = mod("core")
  .dependsOn(testkit % Test)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsV,
      "org.apache.commons" % "commons-numbers-gamma" % commonsNumbersV,
      "org.apache.commons" % "commons-numbers-core" % commonsNumbersV
    )
  )

lazy val ad = mod("ad").dependsOn(core, testkit % Test)

// L'oracle `Dual` vit dans les tests de `ad` et sert aux tests d'aval (section 4 du design).
lazy val distributions =
  mod("distributions").dependsOn(ad, ad % "test->test", testkit % Test)

// The `Dual` oracle lives in `ad`'s tests and the gradient of a model's target is checked against
// it. `distributions` is a **test** dependency only: `Model` itself is abstract and needs no
// density, but a model to test it on does, and the published `transforms` stays independent of
// `distributions` (docs/design.md).
lazy val transforms = mod("transforms")
  .dependsOn(ad, ad % "test->test", distributions % Test, testkit % Test)

// `transforms % "test->test"` shares the eight schools and the model carrying one block of every
// constraint: `Init.PriorMedian` needs a model with a generative part to be tested on, and that
// model already exists with its own tests behind it.
lazy val inference = mod("inference")
  .dependsOn(transforms, transforms % "test->test", distributions, testkit % Test)

// `inference % "test->test"` and `transforms % "test->test"` share the targets and the eight
// schools: a summary has to be checked on a real fit, and those models already have their own
// tests behind them.
lazy val diagnostics = mod("diagnostics")
  .dependsOn(
    inference,
    inference % "test->test",
    transforms % "test->test",
    // A **test** dependency only: the posteriordb comparison of phase 6 has to read the reference
    // summaries, and `PosteriordbSummary` lives in `stan-io`. The published `diagnostics` stays
    // independent of it (docs/design.md).
    stanIo % Test,
    testkit % Test
  )

// `scoring % Test` only: `ModelError.calibrate` takes its scoring rule as an argument, so nothing
// here depends on `scoring` — but its test passes `Crps.ensemble`, the rule anyone would really
// use (docs/design.md).
lazy val predictive =
  mod("predictive").dependsOn(distributions, scoring % Test, testkit % Test)

lazy val scoring = mod("scoring").dependsOn(distributions, testkit % Test)

// `ad % "test->test"` for the dual-number oracle: the count model's gradient is checked against it
// the way the ported posteriors' are (docs/design.md).
// `diagnostics` and `stan-io` in Test only: the slow suites compare against a reference summary,
// which needs the convergence diagnostics to guard the comparison and the summary parser to read
// it. Nothing in `nowcast`'s own sources depends on either (docs/design.md).
lazy val nowcast =
  mod("nowcast").dependsOn(
    inference,
    predictive,
    ad % "test->test",
    diagnostics % Test,
    scoring % Test,
    stanIo % Test,
    testkit % Test
  )

// `Draws` lives in `core` (docs/design.md, D-5), so `stan-io` does not need `predictive`.
lazy val stanIo = mod("stan-io")
  .dependsOn(core, testkit % Test)
  .settings(libraryDependencies += "io.circe" %% "circe-parser" % circeV)

// --- the effectful edge -------------------------------------------------------------------

lazy val effect = mod("effect")
  .dependsOn(nowcast, diagnostics, scoring, stanIo, testkit % Test)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectV,
      "co.fs2" %% "fs2-core" % fs2V,
      "co.fs2" %% "fs2-io" % fs2V,
      "org.gnieh" %% "fs2-data-csv" % fs2DataV
    )
  )

// --- modules that are never published -----------------------------------------------------

// `testkit` depends on no bayes4s module, so every module including `core` can depend on it in
// Test without a cycle (docs/design.md, D-4). It carries the reference data on its classpath.
lazy val testkit = mod("testkit")
  .enablePlugins(NoPublishPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsV,
      "org.typelevel" %% "weaver-cats" % weaverV,
      "io.circe" %% "circe-parser" % circeV
    )
  )

// The benchmarked models are the test models of `distributions` (`TestModels`): a benchmark that
// measured a model written for it would measure something no suite checks. `compile->test` is what
// puts them on the benchmark classpath.
lazy val benchmarks = mod("benchmarks")
  .enablePlugins(JmhPlugin, NoPublishPlugin)
  .dependsOn(nowcast, distributions % "compile->test")

lazy val examples = mod("examples")
  .enablePlugins(NoPublishPlugin)
  .dependsOn(effect)

lazy val docs = project
  .in(file("docs"))
  .enablePlugins(MdocPlugin, NoPublishPlugin)
  .dependsOn(effect)
  .settings(
    name := "bayes4s-docs",
    mdocIn := file("docs/manual"),
    mdocOut := file("docs/target/manual")
  )
