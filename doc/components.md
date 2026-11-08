# JSatTrak Component & Package Reference

This document provides a detailed catalog of the packages, classes, and responsibilities across the JSatTrak codebase.

---

## 1. Package Hierarchy

```
src/main/java/
├── jsattrak/
│   ├── about/             # About dialog and system credits
│   ├── coverage/          # Earth coverage grid, revisit calculations, color maps
│   ├── customsat/         # Custom satellite definition, thrust/state vector editors
│   ├── gui/               # Swing desktop GUI, 2D/3D viewers, dialogs, tracking tools
│   ├── objects/           # Domain entities: satellites, ground stations
│   └── utilities/         # TLE parsers, persistence, file transfer handlers, renderables
├── name/gano/
│   ├── astro/             # Astrodynamics mathematics, constants, SGP4, ODE solvers
│   │   ├── bodies/        # Celestial bodies (Sun, Moon)
│   │   ├── coordinates/   # ECI J2000, TEME coordinate frames
│   │   ├── propogators/   # SGP4 CSSI & numerical integrators
│   │   └── time/          # Time standards, Julian Day representations
│   ├── file/              # File I/O utilities, image savers
│   ├── math/              # Interpolation, polynomial solvers, vector operations
│   ├── swingx/            # Swing extension components, fullscreen helpers
│   └── worldwind/         # Extensions for NASA WorldWind integration
├── jguiserver/            # Socket server for external automation/remote control
└── commandclient/         # CLI client sending commands to jguiserver
```

---

## 2. Core Packages & Classes

### 2.1 `jsattrak.gui` (GUI & Interaction)

| Class | Description |
|---|---|
| [`JSatTrak`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/gui/JSatTrak.java) | Main entry point (`JFrame`). Coordinates the MDI desktop, simulation clock loop, object collections, toolbars, and menus. |
| [`J2DEarthPanel`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/gui/J2DEarthPanel.java) | 2D cartographic view. Renders map projection, sub-satellite footprints, day/night line, and orbit ground tracks. |
| [`J2DPolarEarthPanel`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/gui/J2DPolarEarthPanel.java) | 2D azimuthal equidistant polar projection view (North, South, and Dual-Pole modes) with vector coastlines, polar grid, solar terminator, ground tracks, and footprints. |
| [`J2dPolarEarthLabel`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/gui/J2dPolarEarthLabel.java) | Java2D rendering component for the 2D polar projection discs and coordinate transformations. |
| [`TwoDPolarViewPropertiesPanel`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/gui/TwoDPolarViewPropertiesPanel.java) | Configuration dialog panel for customizing 2D Polar Projection Window properties. |
| [`J3DEarthPanel`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/gui/J3DEarthPanel.java) | 3D virtual globe using NASA WorldWind. Displays 3D orbit lines, ground stations, Earth elevation, and supports Earth, Model-following, and Ground Station-to-Satellite camera views. |
| [`J3DEarthInternalPanel`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/gui/J3DEarthInternalPanel.java) | Internal frame wrapper for embedding 3D WorldWind windows inside the MDI desktop. |
| [`GroundStationToSatView`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/name/gano/worldwind/view/GroundStationToSatView.java) | First-person 3D WorldWind camera view anchored at a Ground Station and dynamically oriented along the line-of-sight to a target Satellite. |
| [`GroundStationViewInputHandler`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/name/gano/worldwind/view/GroundStationViewInputHandler.java) | Custom WorldWind input handler providing mouse-wheel FOV telescope zoom, drag Az/El angular offsets, and double-click/keyboard reset for `GroundStationToSatView`. |
| [`GroundStationHudLayer`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/name/gano/worldwind/layers/Earth/GroundStationHudLayer.java) | WorldWind 2D/3D overlay layer rendering the antenna/telescope boresight reticle, projected horizon and elevation mask rings, compass markers, and live AER telemetry panel. |
| [`JTrackingPanel`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/gui/JTrackingPanel.java) | Ground station pass tracker. Computes rise/set/culmination times, Az/El angles, Doppler shifts, and polar plots. |
| [`JPolarPlotLabel`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/gui/JPolarPlotLabel.java) | Radar-style polar radar display representing sky passes from a ground station perspective. |
| [`JSatBrowser`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/gui/JSatBrowser.java) | Tree/list browser for browsing available satellite TLE catalogs and adding them to the simulation. |
| [`JGroundStationBrowser`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/gui/JGroundStationBrowser.java) | Interface to manage and select ground tracking stations. |
| [`JCoverageDialog`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/gui/JCoverageDialog.java) | Configuration and execution dialog for global and regional sensor coverage simulations. |
| [`JCreateMovieDialog`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/gui/JCreateMovieDialog.java) | Frame recorder tool compiling simulation frames into MP4 videos using JCodec. |

---

### 2.2 `jsattrak.objects` (Domain Model)

| Class | Description |
|---|---|
| [`AbstractSatellite`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/objects/AbstractSatellite.java) | Common base class for satellites. Defines properties for name, current time, ECEF/J2000 state vectors, and visualization attributes. |
| [`SatelliteTleSGP4`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/objects/SatelliteTleSGP4.java) | Satellite propelled by NORAD SGP4/SDP4 models from Two-Line Element sets. Computes lead/lag ground tracks. |
| [`CustomSatellite`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/objects/CustomSatellite.java) | User-defined satellite model. Supports arbitrary initial state vectors, Keplerian elements, and numerical propagation. |
| [`GroundStation`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/objects/GroundStation.java) | Earth ground tracking station defined by Latitude, Longitude, Altitude, and Minimum Elevation angle. Computes AER relative to satellites. |

---

### 2.3 `name.gano.astro` (Astrodynamics Library)

| Class / Package | Description |
|---|---|
| [`AstroConst`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/name/gano/astro/AstroConst.java) | Astronomical constants (Earth mass, radius, gravitational parameter $\mu$, speed of light, J2-J4 spherical harmonics, WGS84 flattening). |
| [`JulianDay`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/name/gano/astro/JulianDay.java) / [`Time`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/name/gano/astro/time/Time.java) | Accurate high-precision time calculations, Gregorian-to-Julian conversions, sidereal time equations. |
| [`Kepler`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/name/gano/astro/Kepler.java) | Solves Kepler's equation (eccentric anomaly $E$ from mean anomaly $M$) and converts between orbital elements and Cartesian vectors. |
| [`GeoFunctions`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/name/gano/astro/GeoFunctions.java) | Coordinate transforms between Geodetic (Lat, Lon, Alt) and Geocentric Cartesian (ECEF), distance routines, and sub-satellite footprint radius. |
| [`AER`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/name/gano/astro/AER.java) | Topocentric Azimuth, Elevation, Range vector data structure and conversion routines. |
| `name.gano.astro.propogators.sgp4_cssi` | CSSI (Center for Space Standards & Innovation) implementation of the revised Spacetrack Report #3 SGP4/SDP4 algorithm by David Vallado et al. |
| `name.gano.astro.propogators.solvers` | Numerical ODE solvers including Runge-Kutta 4th order, Runge-Kutta 7-8th order (Dormand-Prince / Fehlberg), and multi-step Adams-Bashforth-Moulton. |
| `name.gano.astro.bodies` | Celestial models calculating ephemeris vectors for the Sun and Moon. |

---

### 2.4 `jsattrak.coverage` (Coverage & Sensor Analysis)

| Class | Description |
|---|---|
| [`CoverageAnalyzer`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/coverage/CoverageAnalyzer.java) | Manages latitude/longitude grid point bins across Earth's surface to evaluate percentage of time covered, gap duration, and revisit frequency. |
| [`ColorMap`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/coverage/ColorMap.java) | Base class and palette implementations (`HotColorMap`, `CoolColorMap`, `GrayColorMap`) for false-color heat map rendering. |

---

### 2.5 `jsattrak.utilities` (Persistence, Data, Rendering Helpers)

| Class | Description |
|---|---|
| [`JstSaveClass`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/utilities/JstSaveClass.java) | Scenario serialization container. Serialized via XStream into `.jst` files. |
| [`J2DPolarEarthPanelSave`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/utilities/J2DPolarEarthPanelSave.java) | Persistence state container for saving and restoring 2D Polar Projection windows in `.jst` scenario files. |
| [`TLEDownloader`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/utilities/TLEDownloader.java) | Automated HTTP fetcher that downloads active satellite TLE lists from CelesTrak and Space-Track. |
| [`OrbitModelRenderable`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/utilities/OrbitModelRenderable.java) | NASA WorldWind renderable pipeline for drawing orbital paths in 3D. |
| [`ECEFModelRenderable`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/utilities/ECEFModelRenderable.java) | NASA WorldWind renderable for drawing 3D spacecraft bodies and pointing geometry. |
| [`LafChanger`](file:///Users/ffelix/Movistar%20Cloud/Workspaces/Java/JSatTrak/src/main/java/jsattrak/utilities/LafChanger.java) | Dynamic Swing Look-and-Feel switcher (FlatLaf, Metal, Nimbus, System). |
