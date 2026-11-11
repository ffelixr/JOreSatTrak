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

1. **Orekit Space Dynamics Engine**: Replaced custom legacy propagation routines with the industry-standard, flight-proven Orekit library (`13.1.8` / Hipparchus `4.0.3`).
2. **NASA WorldWind 2.2.1 & JOGL 2.6.0**: Upgraded from legacy WorldWind 0.5.0/0.6.0 and JOGL 1.1.1 to WorldWind 2.2.1 and JogAmp JOGL 2.6.0, resolving OpenGL rendering incompatibilities, HiDPI/Retina display scaling issues, and adding native ARM64 / Apple Silicon support.
3. **Independent 3D Contexts**: Replaced obsolete shared-canvas context initialization (`WorldWindowGLCanvas` / `GLJPanel`) with decoupled GL initialization, resolving `IllegalStateException: Null shared GLContext` crashes and restoring full 3D Earth rendering in both internal and external windows.
4. **Modern Java Tooling**: Full Apache Maven build lifecycle targeting Java 17 (`maven.compiler.release=17`), deprecation cleanup, and compatibility with modern JDKs (Java 17, 21+).
5. **2D Polar Projection Window**: Added a dedicated Azimuthal Equidistant 2D Polar Projection view (**Windows -> New 2D Polar Window**) supporting North Pole, South Pole, and Dual-Pole (North & South) modes, with vector landmass coastlines, concentric polar lat/lon grids, solar terminator shading, and continuous high-latitude ground track and footprint rendering across the poles and the $\pm 180^\circ$ meridian.
6. **3D Ground Station-to-Satellite Viewport**: Added a first-person 3D observer camera mode (**Camera Following -> Ground Station to Sat** in 3D View Properties, or **3D GS->Sat View** in the Tracking Tool) that anchors the camera at a Ground Station and dynamically tracks a target Satellite with an antenna/telescope boresight HUD (reticle, cardinal compass markers, $0^\circ$ horizon and elevation-mask rings, live AER telemetry, horizon clamping, and interactive FOV zoom and angular offset controls).

---

## Project Dependencies

The project is built with **Apache Maven** (`pom.xml`) targeting **Java 17+**. Dependencies are resolved from Maven Central and the self-contained in-project local Maven repository ([`repo/`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/repo)) for offline/reproducible builds:

| Library | Version | Source | Purpose |
| :--- | :--- | :--- | :--- |
| **Orekit** | `13.1.8` | Maven Central | High-fidelity astrodynamics, numerical/analytical orbit propagation, and coordinate frames |
| **Hipparchus** | `4.0.3` | Maven Central *(via Orekit)* | Mathematics, ODE integrators, linear algebra, and geometry |
| **NASA WorldWind Java (`worldwind`, `worldwindx`)** | `2.2.1` | Local `repo/` | 3D virtual globe GIS rendering |
| **JogAmp JOGL (`jogl-all`) & GlueGen (`gluegen-rt`)** | `2.6.0` | Local `repo/` | OpenGL bindings & native libraries (`macosx-universal` [arm64 + x86_64], `windows-amd64`, `linux-amd64`, `linux-aarch64`) |
| **JOGL Utils (`joglutils`)** | `1.0` | Local `repo/` | Legacy 3D `.3ds`/`.obj` model parsing |
| **FlatLaf** | `3.5.4` | Maven Central | Modern light/dark Swing Look and Feel |
| **JCodec (`jcodec`, `jcodec-javase`)** | `0.2.5` | Maven Central | Pure-Java MP4 video export |
| **JAMA** | `1.0.2` | Maven Central | Legacy matrix algebra utilities |
| **XStream & XPP3** | `1.3` / `1.1.4c` | Maven Central | XML serialization for `.jst` scenario files |
| **BeanShell (`bsh`)** | `2.0b4-seg` | Local `repo/` | Embedded Java scripting console |

---

## macOS & Apple Silicon (arm64) Considerations

NASA WorldWind Java (WWJ 2.2.1) and JogAmp JOGL (2.6.0) rely on native OpenGL binaries (`gluegen-rt` and `jogl-all`).

* **Native Apple Silicon Support:** JOGL 2.6.0 ships `natives-macosx-universal` containing true universal Mach-O binaries with both **`arm64`** and **`x86_64`** slices, as well as Retina/HiDPI display scaling fixes.
* **No Rosetta 2 Required:** Apple Silicon Macs (M1/M2/M3/M4) run natively using a standard `arm64` JDK (Java 17 or 21+) without `arch -x86_64` emulation.
* **Java Module Access (`--add-opens`):** On Java 17+, JOGL requires reflective access to internal AWT/Java2D and NIO packages. Use the provided launcher script or include the module flags manually:
  ```bash
  ./JOreSatTrak_run.sh
  ```
  Or manually:
  ```bash
  java \
    --add-opens java.desktop/sun.awt=ALL-UNNAMED \
    --add-opens java.desktop/sun.java2d=ALL-UNNAMED \
    --add-opens java.desktop/java.awt=ALL-UNNAMED \
    --add-opens java.desktop/javax.swing=ALL-UNNAMED \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.nio=ALL-UNNAMED \
    -jar target/joresattrak-0.1.jar
  ```

---

## Documentation

- **[System Architecture](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md)**: Detailed breakdown of subsystems, data flows, and design patterns.
- **[Component Reference](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/components.md)**: Index of key classes and modules.
- **[Original Notes](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/original_jsattrak/README.txt)**: Historical notes and references from the original JSatTrak distribution.

---

## Building and Running

### Prerequisites
- Java JDK 17 or higher (native `arm64` or `x86_64` on macOS, Windows, and Linux)
- Maven 3.6+

### Build:
```bash
mvn clean package -DskipTests
```

### Run:
Use the launcher script for your platform:

**macOS / Linux / Git Bash:**
```bash
./JOreSatTrak_run.sh
```

**Windows 11 / Windows (CMD, PowerShell, or double-click in Explorer):**
```cmd
JOreSatTrak_run.bat
```

#### Customizing the Launcher via Environment Variables
The launcher scripts (`JOreSatTrak_run.sh` and `JOreSatTrak_run.bat`) honor the following environment variables:

| Variable | Description | Example (macOS/Linux) | Example (Windows) |
| :--- | :--- | :--- | :--- |
| `JORE_JAVA_HOME` | Path to preferred JDK home | `export JORE_JAVA_HOME=/usr/lib/jvm/java-21` | `set JORE_JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17` |
| `JORE_JAVA_BIN` | Explicit path to `java` executable (overrides `JORE_JAVA_HOME`) | `export JORE_JAVA_BIN=/opt/jdk-21/bin/java` | `set JORE_JAVA_BIN=C:\Java\jdk-17\bin\java.exe` |
| `JORE_JVM_OPTS` | Extra flags passed to the JVM | `export JORE_JVM_OPTS="-Xmx2g"` | `set JORE_JVM_OPTS=-Xmx2g` |
| `JORE_JAR` | Custom path to packaged JAR | `export JORE_JAR=target/joresattrak-0.1.jar` | `set JORE_JAR=target\joresattrak-0.1.jar` |

On Linux / Windows, you can also launch the JAR directly:
```bash
java -jar target/joresattrak-0.1.jar
```
