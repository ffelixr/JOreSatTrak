# JOreSatTrak

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

- **Multi-View 2D and 3D Visualization:** 2D ground track maps (custom Swing engine) and 3D interactive virtual globe powered by NASA WorldWind and JOGL.
- **Orbit Propagation:**
  - SGP4 / SDP4 analytical orbit propagation from two-line element sets (TLE).
  - Modernized numerical and analytical propagation via the **Orekit** space dynamics library.
- **Coverage & Pass Prediction:** Ground station visibility, contact pass times, and satellite-to-satellite line-of-sight analysis.
- **Custom Scripting:** BeanShell scripting console and embedded command server.
- **Modern Look & Feel:** Integrated FlatLaf UI theme.

---

## Documentation

- **[System Architecture](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md)**: Detailed breakdown of subsystems, data flows, and design patterns.
- **[Component Reference](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/components.md)**: Index of key classes and modules.
- **[Original Notes](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/README.txt)**: Historical notes and references from the original JSatTrak distribution.

---

## Building and Running

### Prerequisites
- Java JDK 8 or higher
- Maven 3.6+

### Build:
```bash
mvn clean package
```

### Run:
```bash
mvn exec:java
```
Or execute the packaged JAR:
```bash
java -jar target/joresattrak-0.1.jar
```
