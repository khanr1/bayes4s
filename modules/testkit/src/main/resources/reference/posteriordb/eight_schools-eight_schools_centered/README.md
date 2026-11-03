No reference draws: posteriordb deliberately has none for the centered parameterization, because
Stan cannot sample it reliably. Use it as a failure-mode test: with default settings the sampler
must report divergences (GUIDE Step 10), while the non-centered version must report none.
