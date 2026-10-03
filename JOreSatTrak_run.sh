#!/bin/bash
set -e

# -----------------------------------------------------------------------------
# JOreSatTrak Launch Script
#
# Supports:
#   - macOS (Darwin): Defaults to x86_64 Java runtime under Rosetta 2
#                     (required by JOGL 2.4.0 / NASA WorldWind macOS binaries)
#   - Linux: Native Java execution
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
    # macOS: Default to Temurin x86_64 if not specified
    if [ -z "$JORE_JAVA_BIN" ] && [ -z "$JORE_JAVA_HOME" ] && [ -z "$JAVA_HOME" ]; then
      MAC_DEFAULT_X86_JAVA="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java"
      if [ -x "$MAC_DEFAULT_X86_JAVA" ]; then
        JAVA_BIN="$MAC_DEFAULT_X86_JAVA"
      else
        JAVA_BIN="$(resolve_java_bin)"
      fi
    else
      JAVA_BIN="$(resolve_java_bin)"
    fi

    if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
      echo "Error: Java executable not found."
      echo "Please set JORE_JAVA_HOME or JORE_JAVA_BIN to an x86_64 JDK installation."
      exit 1
    fi

    # Determine CPU architecture
    HOST_ARCH="$(uname -m)"
    echo "[JOreSatTrak] Launching on macOS ($HOST_ARCH) using: $JAVA_BIN"

    if [ "$HOST_ARCH" = "arm64" ]; then
      # Execute through Rosetta 2 x86_64 emulator for JOGL native compatibility
      exec arch -x86_64 "$JAVA_BIN" \
        "${DEFAULT_JVM_OPTS[@]}" \
        ${JORE_JVM_OPTS} \
        -jar "$JAR_FILE" "$@"
    else
      exec "$JAVA_BIN" \
        "${DEFAULT_JVM_OPTS[@]}" \
        ${JORE_JVM_OPTS} \
        -jar "$JAR_FILE" "$@"
    fi
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
