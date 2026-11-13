# Confirms the two rules the rethinking reference marks `confirm_with_R`.
#
#   Rscript reference/confirm-rethinking-with-r.R
#
# Needs R with `coda` and `loo` from CRAN, and the nine HPDI cases as a CSV (the Python one-liner
# in `docs/validation.md` writes it). It **runs** those packages and compares numbers; no package
# source is read, which is the rule the `rethinking` module is held to (docs/design.md, D-162).
#
# It deliberately does not need the `rethinking` package itself: `coda::HPDinterval` is the
# algorithm §5.13 names, and `loo::waic` is an independent implementation of the same published
# WAIC formula. The result of a run is recorded in docs/validation.md.

# Confirming two reference rules against R itself. This *runs* coda and base R and compares
# numbers; no package source is read. (bayes4s docs/design.md, D-162)
suppressMessages(library(coda))

rows <- read.csv("/private/tmp/claude-501/-Users-raphaelkhan-Developer-bayes4s/666f8a38-be12-442f-b4aa-be56d6cb96b5/scratchpad/hpdi_cases.csv",
                 stringsAsFactors = FALSE)

cat("--- 1. coda::HPDinterval against the reference's nine cases ---\n")
worst <- 0
for (i in seq_len(nrow(rows))) {
  x <- as.numeric(strsplit(rows$samples[i], ";")[[1]])
  got <- HPDinterval(as.mcmc(x), prob = rows$prob[i])
  lo <- got[1, "lower"]; hi <- got[1, "upper"]
  refLo <- as.numeric(rows$ref_low[i]); refHi <- as.numeric(rows$ref_high[i])
  gap <- max(abs(lo - refLo), abs(hi - refHi))
  worst <- max(worst, gap)
  cat(sprintf("%-14s prob %.2f  coda [%.10f, %.10f]  ref [%.10f, %.10f]  %s\n",
              rows$case[i], rows$prob[i], lo, hi, refLo, refHi,
              if (gap == 0) "identical" else sprintf("gap %.3e", gap)))
}
cat(sprintf("worst gap over nine cases: %.3e\n\n", worst))

cat("--- 2. the index rule coda actually uses ---\n")
# Reconstruct it from coda's answers rather than from its source: for each case, which `npoints`
# reproduces the interval coda returned?
for (i in seq_len(nrow(rows))) {
  x <- sort(as.numeric(strsplit(rows$samples[i], ";")[[1]]))
  n <- length(x); p <- rows$prob[i]
  got <- HPDinterval(as.mcmc(x), prob = p)
  found <- NA
  for (np in seq_len(n - 1)) {
    widths <- x[(np + 1):n] - x[1:(n - np)]
    best <- which.min(widths)
    if (isTRUE(all.equal(x[best], got[1, "lower"])) &&
        isTRUE(all.equal(x[best + np], got[1, "upper"]))) { found <- np; break }
  }
  cat(sprintf("%-14s n %4d prob %.2f  npoints %4d   floor %4d  ceil %4d  round %4d\n",
              rows$case[i], n, p, found, floor(n * p), ceiling(n * p), round(n * p)))
}

cat("\n--- 3. tie-breaking: two windows of exactly equal width ---\n")
# 0, 1, 2 ... 9 plus a second equally tight stretch. Every window of the same length has the same
# width here, so the choice is entirely the tie-break.
tied <- as.numeric(0:9)
for (p in c(0.5, 0.89)) {
  got <- HPDinterval(as.mcmc(tied), prob = p)
  cat(sprintf("prob %.2f  coda [%g, %g]   (first window would be [%g, %g])\n",
              p, got[1, "lower"], got[1, "upper"],
              tied[1], tied[1 + max(1, round(length(tied) * p))]))
}

cat("\n--- 4. R's var divides by n - 1, which is what WAIC's standard error needs ---\n")
v <- c(1, 2, 3, 4, 10)
cat(sprintf("var(x) = %.10f ; sum((x-mean)^2)/(n-1) = %.10f ; /n = %.10f\n",
            var(v), sum((v - mean(v))^2) / (length(v) - 1), sum((v - mean(v))^2) / length(v)))

# --- 5. WAIC and its standard error, against `loo`, an independent implementation -------------
# Needs the reference's 4000 x 20 log-likelihood matrix as a headerless CSV; extract it with
#   python3 -c "import json;d=json.load(open('modules/testkit/src/main/resources/reference/rethinking/rethinking.json'));
#   print('\n'.join(','.join(repr(v) for v in r) for r in d['waic']['log_likelihood']))" > loglik.csv
if (requireNamespace("loo", quietly = TRUE) && file.exists("loglik.csv")) {
  suppressMessages(library(loo))
  m <- as.matrix(read.csv("loglik.csv", header = FALSE))
  print(suppressWarnings(waic(m))$estimates)
  pointwise <- -2 * (apply(m, 2, function(col) log(mean(exp(col)))) - apply(m, 2, var))
  cat(sprintf("se with R's var (n-1):     %.10f\n", sqrt(ncol(m) * var(pointwise))))
  cat(sprintf("se with population var:    %.10f\n",
              sqrt(ncol(m) * mean((pointwise - mean(pointwise))^2))))
}
