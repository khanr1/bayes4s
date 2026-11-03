#!/usr/bin/env bash
# Regenerates every file under testkit/src/main/resources/reference. Not run by sbt.
# Usage: reference/regenerate.sh <dir with clones of stan-dev/math, stan-dev/stan, stan-dev/posteriordb>
set -euo pipefail
EXT="$1"; HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/../testkit/src/main/resources/reference"
python -I "$HERE/extract_stan_math_distributions.py" "$EXT/math" "$OUT/stan-math/distributions.json"
python -I "$HERE/build_transform_fixtures.py" "$OUT/stan-math/transforms.json"
python -I "$HERE/build_diagnostics_fixtures.py" "$EXT/stan" "$OUT/stan/diagnostics"
python -I "$HERE/build_hmc_fixtures.py" "$EXT/stan" "$OUT/stan/hmc/hmc.json"
python -I "$HERE/build_posteriordb_fixtures.py" "$EXT/posteriordb" "$OUT/posteriordb"
python -I "$HERE/build_python_fixtures.py" "$OUT/python"
