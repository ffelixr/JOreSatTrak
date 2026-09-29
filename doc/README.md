# JSatTrak Architecture Documentation

This directory contains technical documentation describing the architecture, design, and key components of **JSatTrak**, a Java-based satellite tracking, orbit propagation, and visualization system originally developed by Shawn E. Gano.

---

## Table of Contents

1. [System Overview & Purpose](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md#system-overview)
2. [High-Level Architecture](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md#high-level-architecture)
   - Architectural Style (Desktop MDI + Component Model)
   - Module & Package Breakdown
3. [Core Subsystems & Technical Details](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md#core-subsystems)
   - [Astrodynamics & Orbit Propagation Engine](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md#1-astrodynamics--propagation-engine-nameganoastro)
   - [Domain Model & Entity Management](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md#2-domain-model-and-entities-jsattrakobjects)
   - [Visualization Subsystems (2D and 3D)](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md#3-visualization-subsystems-2d-and-3d)
   - [Coverage Analysis & Sensor Footprints](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md#4-coverage-analysis-jsattrakcoverage)
   - [Time & Simulation Loop](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md#5-time-system-and-simulation-loop)
   - [Scripting & External Automation Interface](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md#6-scripting--external-automation-interface)
   - [Persistence & State Serialization](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md#7-persistence-and-serialization)
4. [Technology Stack & Third-Party Dependencies](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md#technology-stack--third-party-dependencies)
5. [Directory Layout](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md#project-directory-structure)

---

## Documents

* **[doc/architecture.md](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/architecture.md)** — Comprehensive architecture breakdown, subsystem interactions, class relationships, and sequence flows.
* **[doc/components.md](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/doc/components.md)** — Detailed component reference mapping key classes, interfaces, and data structures.
