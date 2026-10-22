#!/usr/bin/env bash
# Selects the pinned JDK 25 build used for builds and tests.
# Run: source scripts/java-env.sh
set -euo pipefail
JAVA25_HOME="${JAVA25_HOME:-$HOME/.local/share/campuslost/jdk-25.0.5+8}"
if [[ ! -x "$JAVA25_HOME/bin/javac" ]]; then
  echo "JDK 25 is not installed at $JAVA25_HOME." >&2
  echo "Run scripts/bootstrap-java.sh first." >&2
  return 1 2>/dev/null || exit 1
fi
export JAVA_HOME="$JAVA25_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
echo "JAVA_HOME=$JAVA_HOME"
