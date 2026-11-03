# Design decisions

One section per decision taken while building the library, in the order they were taken. A
decision is recorded here when it constrains later work: a public signature, a dependency, a
layout, a deviation from what was planned. Where a decision departs from the original design, the
reason is given, so that reversing it is a deliberate act rather than an accident.

This file is the reference for the repository. The planning document that preceded it is not part
of the repository and is never cited from it.

---

## Phase 0 — skeleton

### D-1. Name, licence, native bindings, publishing

- The library is named **bayes4s**: root package `bayes4s`, artefacts `bayes4s-core`,
  `bayes4s-ad`, and so on, and the stress flag `-Dbayes4s.stress=true`.
- It is licensed under **Apache 2.0** (`LICENSE`). The BSD-3-Clause texts of Stan, Stan Math and
  posteriordb sit in `LICENSES/`, because reference data copied from those projects lives in
  `modules/testkit/src/main/resources/reference/` and keeps its own licence.
- **No BridgeStan module, and no native dependency at all in v1.** If the sampler ever falls
  behind, the fallback is `stan-io`: read CmdStan draws and run the predictive simulation, the
  scores and the backtest on them.
- **Publishing is off.** The repository stays private for now, so `tlCiReleaseBranches` and
  `tlCiReleaseTags` are empty and `githubWorkflowPublishTargetBranches` is empty: `ci.yml` carries
  no publish job, no Sonatype credentials and no PGP step. There is deliberately no `release.yml`.

  Turning publishing on later means: confirm the decision, set `tlCiReleaseBranches := Seq("main")`
  and `tlCiReleaseTags := true`, pick a group id that matches a verified Sonatype namespace (the
  current `io.github.bayes4s` is a placeholder and has **not** been claimed), fill in `developers`,
  then run `sbt githubWorkflowGenerate` and commit the result.

### D-2. Dependency versions, checked on 3 November 2026

Every version below was read from Maven Central on that day, not from memory.

| What | Version | Note |
|---|---|---|
| Scala | 3.3.9 | The LTS line. 3.3.9 shipped 2026-10-22, three years after 3.3.0, while 3.7.x stopped at 3.7.4 in November 2025 and 3.8, 3.9 and 3.10 are "Next" releases. Patch releases still arriving on 3.3 is what identifies the LTS. |
| sbt | 1.13.1 | sbt 2.0.x exists, but the whole Typelevel plugin set is still published for the sbt 1.x axis. |
| sbt-typelevel | 0.8.7 | Brings sbt-header, sbt-scalafmt, MiMa, and the CI generator. |
| cats / cats-effect | 2.13.0 / 3.7.1 | |
| fs2 / fs2-data-csv | 3.14.0 / 1.14.1 | `effect` only. |
| circe | 0.14.17 | 0.15.0-M1 is a milestone, not a release. |
| commons-numbers | 1.3 | `-gamma` and `-core`, for `logGamma` and `digamma`. |
| weaver | 0.13.0 | On `org.typelevel`; the old `com.disneystreaming` group is archived. |
| scalafmt-core | 3.11.5 | See D-3. |
| sbt-scalafix / sbt-mdoc / sbt-jmh | 0.14.9 / 2.9.2 / 0.4.8 | |

### D-3. The build needs a JDK >= 23; the artefacts target Java 17

`tlJdkRelease := Some(17)`, so anything published stays usable from Java 17 on. The **build**,
however, needs a newer JDK than that: scalafmt 3.11.5 loads a class compiled for Java 23
(`io.github.alexarchambault.isterminal`, pulled in by the coursier that scalafmt-dynamic uses),
and fails with `UnsupportedClassVersionError` on anything older. CI therefore runs on
**temurin 25**, the current LTS.

The machine this skeleton was built on has only Java 20 and Java 22 (Homebrew `openjdk`), so
`sbt scalafmtCheckAll` **cannot run there yet**: it is the one item of phase 0 that is not
verified locally. `sbt headerCheckAll`, `sbt "scalafixAll --check"`, `sbt test` and `sbt slow:test`
all run on Java 22. Installing a JDK 25 (`brew install openjdk@25`) removes the gap; CI is already
set up for it.

### D-4. `testkit` depends on no bayes4s module

Every module, `core` included, declares `dependsOn(testkit % Test)`. For that to hold without a
dependency cycle, `testkit` itself depends on nothing from this build — only on cats, circe and
weaver. Nothing it currently provides needs a bayes4s type:

- `Reference` loads files and returns `Either`, `Json`, `IArray[Double]`;
- `Approx` and `MonteCarlo` return `weaver.Expectations`.

Generators and oracles that *do* need library types stay in the tests of the module that defines
them and travel with `dependsOn(other % "test->test")`: the `Dual` interpreter in the tests of
`ad`, `TestModels` in the tests of `inference`. The original plan had `testkit` depend on `core`,
which would have made `core`'s own tests a cycle.

### D-5. `Draws` belongs in `core`

`stan-io` parses and renders CmdStan CSV into `Draws`, and `predictive` consumes `Draws`. Keeping
`Draws` in `predictive` would force `stan-io` to depend on it for a plain data type. `Draws` is
therefore a `core` type, and `stan-io` depends on `core` alone.

### D-6. Layout: `modules/<name>`

Each module lives in `modules/<name>` rather than at the top level, so the repository root holds
only build files, documentation and the reference scripts. Reference data consequently sits in
`modules/testkit/src/main/resources/reference/`, and the BSD-3 licence texts that came with it
were moved to `LICENSES/` at the root.

### D-7. The private-term guard uses HMAC, and it is a Python script

The rule is absolute: nothing in this repository relates to the maintainer's employer, sector,
products, clients or data. The automated guard works on digests only, so that the list of terms
never enters the repository. Two deviations from the original design:

1. **HMAC-SHA-256 with a key kept outside the repository**, not a bare SHA-256. A bare SHA-256 of
   ordinary words — a company name, a product, a city — falls to a dictionary attack in seconds,
   so publishing such a file would leak exactly what it is meant to protect. With a key that lives
   only in the maintainer's environment and in a CI secret, the digest file reveals nothing.
2. **Python, not scala-cli.** `scripts/check-private-terms.py`, `scripts/hash-private-terms.py`
   and the shared `scripts/private_terms.py` need no toolchain beyond the Python 3 that is already
   present locally and on the runners. The rule that sbt never launches Python still holds: this
   is a CI step, not an sbt task.

Behaviour, in order of strictness:

- `.private-terms.sha256` absent or empty → the check passes and says nothing is configured. That
  is the state the repository ships in: only the maintainer has the term list.
- digests present, no key → **exit 2**. The check refuses to pass silently when it cannot verify.
- a digest matches → exit 1, naming the file, the line and the first bytes of the digest. Never
  the term: the script does not know it, and the CI log must not carry it.

A single-word line is digested as that word; a multi-word line is digested whole and as pairs of
consecutive words, but not word by word, or an ordinary word of a name ("group", "industries")
would fail CI everywhere. To refuse a single word, give it its own line.

The guard only sees the terms it was given. Human review of every pull request that touches
`docs/` or `examples/` remains the real defence.

### D-8. Slow suites are a configuration, not a tag

`sbt test` must stay fast enough to run on every save. The slow statistical suites — SBC,
posteriordb, parameter recovery — therefore live in their own source directory, `src/slow/scala`
of each module, under an sbt configuration `slow` that extends `Test`:

- `sbt test` compiles and runs `src/test/scala` only;
- `sbt slow:test` runs the slow suites, with `Test`'s classpath available;
- `sbt -Dbayes4s.stress=true slow:test` is what `stress.yml` runs weekly, with fresh seeds and
  more draws.

A separate directory rather than tag filtering: a suite is slow or it is not, and a reader can see
which from the file's path.

### D-9. `Reference` returns `Either`, and quoting non-finite tokens respects strings

`Reference.json` was specified as returning `io.circe.Json` directly. It returns
`Either[Reference.Error, Json]` instead, because errors are values here as everywhere else, and a
missing or malformed reference file must fail a suite with a message that names the file rather
than throw. `Error` is an enum with a `message` method; the suites render it.

The reference files use Python's `Infinity`, `-Infinity` and `NaN`, which JSON does not define.
`Reference.json` quotes those tokens before parsing and `Reference.looseDouble` reads them back as
the IEEE values. The subtlety is that the same words occur **inside** JSON strings — for instance
`"every diagnostic must return NaN"` in `stan/diagnostics/expected.json`. Quoting those would
break the string and the parse. The implementation therefore splits the input on string literals
and rewrites only what lies outside them. `ReferenceSuite` asserts both halves of this, and the
mutation recorded in `docs/validation.md` shows the test that catches it.

### D-10. `GammaPoisson` will live in `distributions`, not `nowcast`

The conjugate Gamma-Poisson update and its negative-binomial predictive are needed from phase 1,
where `worked_examples.json` asserts them, while `nowcast` is not built until phase 9. They are a
property of the distributions, not of the nowcasting model, so `GammaPoisson` goes in
`distributions` and only `GammaPoissonShrinkage` stays in `nowcast`.

### D-11. Everything in the repository is written in English

Code, comments, scaladoc, documentation, scripts, commit messages. The manual is English by
design, and a published library with French comments in its sources would be inconsistent.

### D-12. scalafix runs `DisableSyntax` only, for now

`DisableSyntax` is enabled with `noVars`, `noThrows`, `noNulls`, `noReturns`, `noWhileLoops`,
`noAsInstanceOf`, `noIsInstanceOf` and `noFinalize`, and CI fails on any of them. This is the rule
the design depends on, and it is syntactic, so it works on Scala 3 with no SemanticDB.

`OrganizeImports` is **not** enabled yet: it is a semantic rule, its Scala 3 support depends on
SemanticDB being produced the way it expects, and there is almost no import in the tree to
organise. It is to be enabled in phase 1, when there is real code to check it against.

### D-14. CI runs on `ubuntu-latest`, and the dependency-graph job is off

sbt-typelevel 0.8.7 still pins `ubuntu-22.04`, an image GitHub has deprecated; a workflow that
names a retired image does not fail a step, it fails to find a runner at all.
`githubWorkflowOSes := Seq("ubuntu-latest")` is right whether or not that image is still served.

`tlCiDependencyGraphJob := false` removes the "Submit Dependencies" job. It calls an API that
needs `contents: write`, which a repository's default `GITHUB_TOKEN` does not have, so it would
fail on every push to the default branch. It is worth turning back on, with the permission
granted, once Dependabot is configured and the repository is one whose dependency graph anyone
reads.

### D-13. The register of encapsulated local mutation is empty

The one allowed exception to referential transparency is mutation that is created inside a pure
function and never escapes it. Every such site must carry
`// scalafix:ok DisableSyntax.var` (or `.while`) with a sentence of justification, **and** be
listed here.

**There is no such site yet.** The first ones are expected in phase 2 (the reverse-mode tape) and
phase 4 (leapfrog, `build_tree`). The small CI script that compares the annotated sites in the
code with this list is owed at the same time as the first site, and does not exist yet.

| Site | Why mutation | Why it cannot escape |
|---|---|---|
| *(none)* | | |
