#!/bin/bash

JAVA_BIN="/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java"

arch -x86_64 "$JAVA_BIN" \
  --add-opens java.desktop/sun.awt=ALL-UNNAMED \
  --add-opens java.desktop/sun.java2d=ALL-UNNAMED \
  -jar target/joresattrak-0.1.jar "$@"

