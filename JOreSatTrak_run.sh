#!/bin/bash
set -e

# -----------------------------------------------------------------------------
# JOreSatTrak Launch Script
#
# Supports:
#   - macOS (Darwin): Native ARM64 (Apple Silicon) and x86_64 execution with JOGL 2.6.0
#   - Linux: Native Java execution (x86_64 / aarch64)
#   - Windows (Git Bash, MSYS2, Cygwin, MINGW): Native Java execution
#
# Customization Environment Variables:
#   - JORE_JAVA_HOME: Path to JDK directory (e.g., /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home)
#   - JORE_JAVA_BIN : Explicit path to java binary (overrides JORE_JAVA_HOME)
#   - JORE_JAR      : Path to JOreSatTrak JAR (defaults to target/joresattrak-0.1.jar)
#   - JORE_JVM_OPTS : Additional JVM options (e.g., -Xmx2g)
# -----------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

JAR_FILE="${JORE_JAR:-target/joresattrak-0.1.jar}"

if [ ! -f "$JAR_FILE" ]; then
  echo "Error: Application JAR not found at '$JAR_FILE'."
  echo "Please build the project first using: mvn clean package -DskipTests"
  exit 1
fi

# Determine Operating System
OS_TYPE="$(uname -s 2>/dev/null || echo "Unknown")"

# Base JVM opens required for AWT/Java2D and JOGL integration
DEFAULT_JVM_OPTS=(
  --add-opens java.desktop/sun.awt=ALL-UNNAMED
  --add-opens java.desktop/sun.java2d=ALL-UNNAMED
  --add-opens java.desktop/java.awt=ALL-UNNAMED
  --add-opens java.desktop/javax.swing=ALL-UNNAMED
  --add-opens java.base/java.lang=ALL-UNNAMED
  --add-opens java.base/java.nio=ALL-UNNAMED
)

# Resolve Java binary
resolve_java_bin() {
  if [ -n "$JORE_JAVA_BIN" ] && [ -x "$JORE_JAVA_BIN" ]; then
    echo "$JORE_JAVA_BIN"
    return
  fi

  if [ -n "$JORE_JAVA_HOME" ] && [ -x "$JORE_JAVA_HOME/bin/java" ]; then
    echo "$JORE_JAVA_HOME/bin/java"
    return
  fi

  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    echo "$JAVA_HOME/bin/java"
    return
  fi

  # Fallback to PATH
  if command -v java >/dev/null 2>&1; then
    command -v java
    return
  fi

  echo ""
}

case "$OS_TYPE" in
  Darwin*)
    JAVA_BIN="$(resolve_java_bin)"

    if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
      echo "Error: Java executable not found."
      echo "Please install Java 17+ or set JORE_JAVA_HOME / JORE_JAVA_BIN / JAVA_HOME."
      exit 1
    fi

    # Determine CPU architecture
    HOST_ARCH="$(uname -m)"
    echo "[JOreSatTrak] Launching on macOS ($HOST_ARCH) using: $JAVA_BIN"

    exec "$JAVA_BIN" \
      "${DEFAULT_JVM_OPTS[@]}" \
      ${JORE_JVM_OPTS} \
      -jar "$JAR_FILE" "$@"
    ;;

  Linux*)
    JAVA_BIN="$(resolve_java_bin)"
    if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
      echo "Error: Java executable not found."
      echo "Please install Java or set JORE_JAVA_HOME / JORE_JAVA_BIN."
      exit 1
    fi

    echo "[JOreSatTrak] Launching on Linux using: $JAVA_BIN"
    exec "$JAVA_BIN" \
      "${DEFAULT_JVM_OPTS[@]}" \
      ${JORE_JVM_OPTS} \
      -jar "$JAR_FILE" "$@"
    ;;

  CYGWIN*|MINGW*|MSYS*)
    JAVA_BIN="$(resolve_java_bin)"
    if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
      echo "Error: Java executable not found."
      echo "Please install Java or set JORE_JAVA_HOME / JORE_JAVA_BIN."
      exit 1
    fi

    echo "[JOreSatTrak] Launching on Windows (${OS_TYPE}) using: $JAVA_BIN"
    exec "$JAVA_BIN" \
      "${DEFAULT_JVM_OPTS[@]}" \
      ${JORE_JVM_OPTS} \
      -jar "$JAR_FILE" "$@"
    ;;

  *)
    JAVA_BIN="$(resolve_java_bin)"
    if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
      echo "Error: Java executable not found."
      echo "Please install Java or set JORE_JAVA_HOME / JORE_JAVA_BIN."
      exit 1
    fi

    echo "[JOreSatTrak] Launching on $OS_TYPE using: $JAVA_BIN"
    exec "$JAVA_BIN" \
      "${DEFAULT_JVM_OPTS[@]}" \
      ${JORE_JVM_OPTS} \
      -jar "$JAR_FILE" "$@"
    ;;
esac
