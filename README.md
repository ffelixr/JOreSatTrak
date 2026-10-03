# JOreSatTrak - (Work in Progress - No fully functional yet)

**JOreSatTrak** is an open-source satellite tracking, orbit propagation, and visualization application. It is an independent fork of the original **[JSatTrak](http://www.gano.name/shawn/JSatTrak)** application created by **Shawn E. Gano**, modernized to integrate the **[Orekit](https://www.orekit.org/)** space dynamics library alongside modern Java tooling.

---

## Notice & Fork Disclaimer

* **Original Author & Project:** JSatTrak was originally conceived, designed, and developed by **Shawn E. Gano** (Copyright 2007–2018).
* **Fork & Integration:** This project is an independent fork maintained by Fernando Felix-Redondo (`name.ffelixr`) and contributors. It is **not** endorsed by, affiliated with, or an official release of Shawn E. Gano, CS GROUP (Orekit maintainers), or NASA.
* **Orekit Integration:** Integrates [Orekit](https://www.orekit.org/) (CS GROUP and contributors, Apache 2.0 license) for advanced, high-fidelity orbit propagation and astrodynamics.

---

## License

This software is distributed under the terms of the **Apache License, Version 2.0**.
See [LICENSE](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/LICENSE) for the full license text.

Third-party software libraries and their respective copyright notices and licenses are documented in [NOTICE](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/NOTICE).

---

## Key Features

- **Multi-View 2D and 3D Visualization:** 2D ground track maps (custom Swing engine) and 3D interactive virtual globe powered by NASA WorldWind Java and JOGL.
- **Orbit Propagation & Astrodynamics:**
  - **Orekit Integration**: High-fidelity numerical and analytical orbit propagation powered by [Orekit](https://www.orekit.org/) 13.x and Hipparchus. Supports complex perturbation models including Earth gravity field harmonics (Holmes-Featherstone), atmospheric drag models (e.g. Harris-Priester), solar radiation pressure (SRP), third-body perturbations (Sun and Moon), and customizable numerical integrators.
  - **SGP4 / SDP4 Analytical Propagation**: Real-time and offline propagation from Two-Line Element (TLE) sets with modernized Orekit TLE state modeling.
  - **Coordinate Reference Frames**: Precise transformation across standard frames (GCRF/J2000, TEME, ITRF/ECEF, Topocentric AER, and Geodetic Lat/Lon/Alt) using IERS conventions.
  - **Custom Satellite Modeling**: Scenario builder supporting Keplerian orbits, Cartesian state vectors, and user-defined force models.
- **Coverage & Pass Prediction:** Ground station visibility, contact pass times, and satellite-to-satellite line-of-sight analysis.
- **Custom Scripting:** BeanShell scripting console and embedded command server.
- **Modern Look & Feel:** Integrated FlatLaf dark and light UI themes.

---

## Modernization & Enhancements

This fork introduces significant modernization over the original JSatTrak:

1. **Orekit Space Dynamics Engine**: Replaced custom legacy propagation routines with the industry-standard, flight-proven Orekit library.
2. **NASA WorldWind 2.2.1 & JOGL 2.4.0**: Upgraded from legacy WorldWind 0.5.0 and JOGL 1.1.1 to WorldWind 2.2.1 and JogAmp JOGL 2.4.0, resolving major OpenGL rendering and modern display pipeline incompatibilities.
3. **Independent 3D Contexts**: Replaced obsolete shared-canvas context initialization (`WorldWindowGLCanvas` / `GLJPanel`) with decoupled GL initialization, resolving `IllegalStateException: Null shared GLContext` crashes and restoring full 3D Earth rendering in both internal and external windows.
4. **Modern Java Tooling**: Full Apache Maven build lifecycle, deprecation cleanup, and compatibility with modern JDKs (Java 8 through Java 21).

---

## macOS & Apple Silicon (arm64) Considerations

NASA WorldWind Java (WWJ 2.2.1) and JogAmp JOGL (2.4.0) rely on native compiled OpenGL binaries (`gluegen-rt` and `jogl-all`). On macOS, particularly on Apple Silicon (M1/M2/M3/M4 chips, `arm64`):

### Native Architecture Requirement
- JOGL 2.4.0 and JAWT integration require an **`x86_64` (Intel 64-bit)** Java runtime environment when running on macOS.
- If executed under a native `arm64` JVM, JOGL will fail with an `UnsatisfiedLinkError` or `RuntimeException: Unable to initialize JAWT` because the native libraries provided for macOS OpenGL bindings are compiled for `x86_64` (or universal x86_64/i386).

### Running on Apple Silicon (macOS arm64)
To run JOreSatTrak seamlessly on Apple Silicon Macs, run the application using an **`x86_64` JDK via Rosetta 2**:

1. Ensure Rosetta 2 is installed:
   ```bash
   softwareupdate --install-rosetta
   ```
2. Install an `x86_64` JDK (such as Eclipse Temurin 17 or 21 x86_64). For example:
   ```bash
   # Using Homebrew (specify x86_64 architecture)
   arch -x86_64 brew install --cask temurin@21
   ```
3. Run the application via `arch -x86_64` or the provided launcher script:
   ```bash
   ./JOreSatTrak_run.sh
   ```
   Or manually:
   ```bash
   arch -x86_64 /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java \
     --add-opens java.desktop/sun.awt=ALL-UNNAMED \
     --add-opens java.desktop/sun.java2d=ALL-UNNAMED \
     -jar target/joresattrak-0.1.jar
   ```

---

## Documentation

- **[System Architecture](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md)**: Detailed breakdown of subsystems, data flows, and design patterns.
- **[Component Reference](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/components.md)**: Index of key classes and modules.
- **[Original Notes](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/README.txt)**: Historical notes and references from the original JSatTrak distribution.

---

## Building and Running

### Prerequisites
- Java JDK 8 or higher (`x86_64` on macOS)
- Maven 3.6+

### Build:
```bash
mvn clean package -DskipTests
```

### Run:
Use the universal launcher script on any supported OS:
```bash
./JOreSatTrak_run.sh
```

#### Customizing the Launcher via Environment Variables
The launcher script auto-detects the operating system (`macOS / Darwin`, `Linux`, `Windows / MinGW / Cygwin`) and honors the following environment variables:

| Variable | Description | Example |
| :--- | :--- | :--- |
| `JORE_JAVA_HOME` | Path to preferred JDK home | `export JORE_JAVA_HOME=/usr/lib/jvm/java-21` |
| `JORE_JAVA_BIN` | Explicit path to `java` executable (overrides `JORE_JAVA_HOME`) | `export JORE_JAVA_BIN=/opt/jdk-21/bin/java` |
| `JORE_JVM_OPTS` | Extra flags passed to the JVM | `export JORE_JVM_OPTS="-Xmx2g"` |
| `JORE_JAR` | Custom path to packaged JAR | `export JORE_JAR=target/joresattrak-0.1.jar` |

On Linux / Windows, you can also launch the JAR directly:
```bash
java -jar target/joresattrak-0.1.jar
```
