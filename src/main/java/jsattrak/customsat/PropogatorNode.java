/*
 * Propogator Node for Custom Sat Class Mission Designer
 * =====================================================================
 *   This file is part of JSatTrak.
 *
 *   Copyright 2007-2013 Shawn E. Gano
 *   
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *   
 *       http://www.apache.org/licenses/LICENSE-2.0
 *   
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * =====================================================================
 * 
 */

package jsattrak.customsat;

import java.awt.Toolkit;
import java.util.Vector;
import javax.swing.ImageIcon;
import javax.swing.JInternalFrame;
import jsattrak.customsat.gui.PropogatorPanel;
import jsattrak.gui.JSatTrak;
import jsattrak.utilities.StateVector;
import name.gano.astro.AstroConst;
import name.gano.astro.Atmosphere;
import name.gano.astro.GeoFunctions;
import name.gano.astro.GravityField;
import name.gano.astro.Kepler;
import name.gano.astro.MathUtils;
import name.gano.astro.bodies.Moon;
import name.gano.astro.bodies.Sun;
import name.gano.astro.coordinates.CoordinateConversion;
import name.gano.astro.propogators.solvers.ApsisStopCond;
import name.gano.astro.propogators.solvers.OrbitProblem;
import name.gano.astro.propogators.solvers.RungeKutta4;
import name.gano.astro.propogators.solvers.RungeKutta78;
import name.gano.astro.propogators.solvers.StoppingCondition;
import name.gano.astro.time.Time;
import name.gano.swingx.treetable.CustomTreeTableNode;

// Orekit and Hipparchus imports
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.ode.AbstractIntegrator;
import org.hipparchus.ode.nonstiff.ClassicalRungeKuttaIntegrator;
import org.hipparchus.ode.nonstiff.DormandPrince853Integrator;
import org.orekit.bodies.CelestialBodyFactory;
import org.orekit.bodies.OneAxisEllipsoid;
import org.orekit.forces.ForceModel;
import org.orekit.forces.drag.DragForce;
import org.orekit.forces.drag.DragSensitive;
import org.orekit.forces.drag.IsotropicDrag;
import org.orekit.forces.gravity.HolmesFeatherstoneAttractionModel;
import org.orekit.forces.gravity.ThirdBodyAttraction;
import org.orekit.forces.gravity.potential.GravityFieldFactory;
import org.orekit.forces.gravity.potential.NormalizedSphericalHarmonicsProvider;
import org.orekit.forces.radiation.IsotropicRadiationSingleCoefficient;
import org.orekit.forces.radiation.RadiationSensitive;
import org.orekit.forces.radiation.SolarRadiationPressure;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.models.earth.atmosphere.HarrisPriester;
import org.orekit.orbits.CartesianOrbit;
import org.orekit.orbits.Orbit;
import org.orekit.orbits.OrbitType;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.events.AltitudeDetector;
import org.orekit.propagation.events.ApsideDetector;
import org.orekit.propagation.events.NodeDetector;
import org.orekit.propagation.events.handlers.StopOnDecreasing;
import org.orekit.propagation.events.handlers.StopOnIncreasing;
import org.orekit.propagation.numerical.NumericalPropagator;
import org.orekit.propagation.sampling.OrekitFixedStepHandler;
import org.orekit.propagation.sampling.StepHandlerMultiplexer;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.Constants;
import org.orekit.utils.IERSConventions;
import org.orekit.utils.PVCoordinates;
import org.orekit.utils.TimeStampedPVCoordinates;

/**
 *
 * @author sgano
 */
public class PropogatorNode extends CustomTreeTableNode implements OrbitProblem
{  
    // static int to determin which propogator to use
    public static final int HPROP4 = 0;
    public static final int HPROP8 = 1;
    public static final int HPROP78 = 2;
    
    // which prop to use 
    private int propogator = PropogatorNode.HPROP4;
    
    // Hprop settings
    private int n_max = 20;  // degree
    private int m_max = 20;  // order
    private boolean includeLunarPert = true;
    private boolean includeSunPert = true;
    private boolean includeSolRadPress = true;
    private boolean includeAtmosDrag = true;
    private double mass = 1000.0;  // [kg] mass of spacecraft
    private double area = 5.0;     // [m^2]  Cross-section Area
    private double CR = 1.3;     // Solar radiation pressure coefficient
    private double CD = 2.3;     // spacecraft drag coefficient
    private double stepSize = 60.0; // seconds  (ini step for Hprop 7-8)
    // Hprop 7-8 unique
    private double minStepSize = 1.0; // 1 second
    private double maxStepSize = 600.0; //10 minutes
    private double relAccuracy = 1.00e-012; 
    
    private double popogateTimeLen = 86400; // in seconds
    
    // stopping conditions used (Orekit EventDetectors)
    private boolean stopOnApogee = false;
    private boolean stopOnPerigee = false;
    private boolean stopOnAscendingNode = false;
    private boolean stopOnDescendingNode = false;
    private boolean stopOnAltitude = false;
    private double stopAltitudeValue = 100000.0; // meters (e.g. 100 km default)
    
    // private variables used internally only
    private double JD_TT0; // JD_TT at initial time (Julian Date)
    Vector<StateVector> ephemeris;
    
    // USED FOR GOAL CALCULATIONS
    StateVector lastStateVector = null; // last state -- to calculate goal properties
    
    // variables that can be set data ----------
    String[] varNames = new String[]{"Propogation Time [s]"};
    // -----------------------------------------
    
    // parameters that can be used as GOALS ---
    String[] goalNames = new String[]
    {
        "X Position (J2000) [m]",  // element 0
        "Y Position (J2000) [m]",
        "Z Position (J2000) [m]",
        "X Velocity (J2000) [m/s]",
        "Y Velocity (J2000) [m/s]",
        "Z Velocity (J2000) [m/s]",
        "Semimajor axis (Osculating) [m]",
        "Eccentricity (Osculating)",
        "Inclination (Osculating) [deg]",
        "Longitude of the ascending node (Osculating) [deg]",
        "Argument of pericenter (Osculating) [deg]",
        "Mean anomaly (Osculating) [deg]",
        "Orbital Radius [m]",
        "Derivative of Orbital Radius [m/s]", // can be used to find perigee / apogee
        "Latitude [deg]",
        "Longitude [deg]",
        "Altitude [m]",  // element 16
    };
    // ========================================
    
    
    public PropogatorNode(CustomTreeTableNode parentNode)
    {
        super(new String[] {"Propogate","",""}); // initialize node, default values
        // set icon for this type
        setIcon( new ImageIcon(Toolkit.getDefaultToolkit().getImage(getClass().getResource("/icons/customSatIcons/prop.png")) ) );
        //set Node Type
        setNodeType("Propogator");
        
        // add this node to parent - last thing
        if( parentNode != null)
            parentNode.add(this);
        
    } // PropogatorNode
    
    
     // propogate sat (using starting point as last ephemeris pt)
    public void execute(Vector<StateVector> ephemeris)
    {
        this.ephemeris = ephemeris;
        
         // dummy but should do something based on input ephemeris
        //System.out.println("Executing : " + getValueAt(0) );
        
        // last state before propogation
        StateVector lastState = ephemeris.lastElement();
        
        // save initial time of the node ( TT)
        this.setStartTTjulDate(lastState.state[0]);
        
        double[] pos = new double[] {lastState.state[1],lastState.state[2],lastState.state[3]};
        double[] vel = new double[] {lastState.state[4],lastState.state[5],lastState.state[6]};
        
        boolean propSuccess = false;
        
        // time parameters that are shared
        JD_TT0 = lastState.state[0];
        double dt = stepSize; // output and initial step size in seconds

        try {
            // Coordinate frame: EME2000 (J2000)
            Frame j2000 = FramesFactory.getEME2000();
            Frame itrf = FramesFactory.getITRF(IERSConventions.IERS_2010, true);

            // Initial date from TT Julian Date
            AbsoluteDate startDate = new AbsoluteDate(
                    AbsoluteDate.JULIAN_EPOCH,
                    JD_TT0 * 86400.0,
                    TimeScalesFactory.getTT());

            // Initial orbit
            PVCoordinates initialPV = new PVCoordinates(
                    new Vector3D(pos[0], pos[1], pos[2]),
                    new Vector3D(vel[0], vel[1], vel[2]));
            Orbit initialOrbit = new CartesianOrbit(initialPV, j2000, startDate, Constants.WGS84_EARTH_MU);

            // Configure ODE Integrator
            AbstractIntegrator integrator;
            if (propogator == PropogatorNode.HPROP4) {
                // Fixed-step 4th order Runge-Kutta
                integrator = new ClassicalRungeKuttaIntegrator(dt);
            } else {
                // Dormand-Prince 8(5,3) adaptive integrator for HPROP8 and HPROP78
                double[][] tol = NumericalPropagator.tolerances(
                        relAccuracy,
                        initialOrbit,
                        OrbitType.CARTESIAN);
                DormandPrince853Integrator dp853 = new DormandPrince853Integrator(
                        minStepSize,
                        maxStepSize,
                        tol[0],
                        tol[1]);
                dp853.setInitialStepSize(dt);
                integrator = dp853;
            }

            NumericalPropagator numPropagator = new NumericalPropagator(integrator);
            numPropagator.setOrbitType(OrbitType.CARTESIAN);
            numPropagator.setInitialState(new SpacecraftState(initialOrbit, mass));

            // Earth shape for gravity, drag, and solar radiation pressure
            OneAxisEllipsoid earth = new OneAxisEllipsoid(
                    Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                    Constants.WGS84_EARTH_FLATTENING,
                    itrf);

            // 1. Earth Gravity Field
            int degree = Math.min(n_max, 120);
            int order = Math.min(m_max, 120);
            NormalizedSphericalHarmonicsProvider gravityProvider =
                    GravityFieldFactory.getNormalizedProvider(degree, order);
            ForceModel holmesFeatherstone = new HolmesFeatherstoneAttractionModel(itrf, gravityProvider);
            numPropagator.addForceModel(holmesFeatherstone);

            // 2. Third-Body Attractions (Sun and Moon)
            if (includeSunPert) {
                numPropagator.addForceModel(new ThirdBodyAttraction(CelestialBodyFactory.getSun()));
            }
            if (includeLunarPert) {
                numPropagator.addForceModel(new ThirdBodyAttraction(CelestialBodyFactory.getMoon()));
            }

            // 3. Solar Radiation Pressure
            if (includeSolRadPress) {
                RadiationSensitive spacecraft = new IsotropicRadiationSingleCoefficient(area, CR);
                ForceModel srp = new SolarRadiationPressure(
                        CelestialBodyFactory.getSun(),
                        earth,
                        spacecraft);
                numPropagator.addForceModel(srp);
            }

            // 4. Atmospheric Drag
            if (includeAtmosDrag) {
                HarrisPriester atmosphere = new HarrisPriester(CelestialBodyFactory.getSun(), earth);
                DragSensitive spacecraft = new IsotropicDrag(area, CD);
                ForceModel drag = new DragForce(atmosphere, spacecraft);
                numPropagator.addForceModel(drag);
            }

            // Stopping conditions (Orekit EventDetectors)
            if (stopOnApogee) {
                numPropagator.addEventDetector(
                        new ApsideDetector(initialOrbit).withHandler(new StopOnDecreasing()));
            }
            if (stopOnPerigee) {
                numPropagator.addEventDetector(
                        new ApsideDetector(initialOrbit).withHandler(new StopOnIncreasing()));
            }
            if (stopOnAscendingNode) {
                numPropagator.addEventDetector(
                        new NodeDetector(initialOrbit, FramesFactory.getEME2000()).withHandler(new StopOnIncreasing()));
            }
            if (stopOnDescendingNode) {
                numPropagator.addEventDetector(
                        new NodeDetector(initialOrbit, FramesFactory.getEME2000()).withHandler(new StopOnDecreasing()));
            }
            if (stopOnAltitude) {
                numPropagator.addEventDetector(
                        new AltitudeDetector(stopAltitudeValue, earth).withHandler(new StopOnDecreasing()));
            }

            // Step handler: record ephemeris points at stepSize intervals
            numPropagator.getMultiplexer().add(dt, new OrekitFixedStepHandler() {
                @Override
                public void handleStep(SpacecraftState currentState) {
                    AbsoluteDate d = currentState.getDate();
                    double jdTT = d.durationFrom(AbsoluteDate.JULIAN_EPOCH) / 86400.0;
                    PVCoordinates pv = currentState.getPVCoordinates(FramesFactory.getEME2000());
                    Vector3D p = pv.getPosition();
                    Vector3D v = pv.getVelocity();

                    StateVector sv = new StateVector(new double[] {
                            jdTT,
                            p.getX(), p.getY(), p.getZ(),
                            v.getX(), v.getY(), v.getZ()
                    });
                    ephemeris.add(sv);
                }
            });

            // Target propagation date
            AbsoluteDate targetDate = startDate.shiftedBy(popogateTimeLen);

            // Execute propagation
            SpacecraftState finalState = numPropagator.propagate(targetDate);

            // Ensure last state is recorded
            PVCoordinates finalPV = finalState.getPVCoordinates(FramesFactory.getEME2000());
            double finalJdTT = finalState.getDate().durationFrom(AbsoluteDate.JULIAN_EPOCH) / 86400.0;
            StateVector finalSv = new StateVector(new double[] {
                    finalJdTT,
                    finalPV.getPosition().getX(), finalPV.getPosition().getY(), finalPV.getPosition().getZ(),
                    finalPV.getVelocity().getX(), finalPV.getVelocity().getY(), finalPV.getVelocity().getZ()
            });

            if (ephemeris.isEmpty() || Math.abs(ephemeris.lastElement().state[0] - finalJdTT) > 1e-12) {
                ephemeris.add(finalSv);
            }

            propSuccess = true;
        } catch (Exception e) {
            System.err.println("Orekit Numerical Propagation error: " + e.getMessage());
            e.printStackTrace();
        }

        // copy final ephemeris state:
        lastStateVector = ephemeris.lastElement();
    }// execute
    
    
    // passes in main app to add the internal frame to
    public void displaySettings(JSatTrak app)
    {
        
        String windowName = "" + getValueAt(0);
        JInternalFrame iframe = new JInternalFrame(windowName,true,true,true,true);
        
        // show satellite browser window
        PropogatorPanel gsBrowser = new PropogatorPanel(this,iframe); // non-modal version       
        
        iframe.setContentPane( gsBrowser );
        iframe.setSize(415+20,386+115); // w,h
        iframe.setLocation(5,5);
        
        app.addInternalFrame(iframe);
          
    } // displaySettings

    
    
    // ==========================================
    // Get-Set Methods ==========================
    // ==========================================
    
    public int getPropogator()
    {
        return propogator;
    }

    public void setPropogator(int propogator)
    {
        this.propogator = propogator;
    }

    public int getN_max()
    {
        return n_max;
    }

    public void setN_max(int n_max)
    {
        this.n_max = n_max;
    }

    public int getM_max()
    {
        return m_max;
    }

    public void setM_max(int m_max)
    {
        this.m_max = m_max;
    }

    public boolean isIncludeLunarPert()
    {
        return includeLunarPert;
    }

    public void setIncludeLunarPert(boolean includeLunarPert)
    {
        this.includeLunarPert = includeLunarPert;
    }

    public boolean isIncludeSunPert()
    {
        return includeSunPert;
    }

    public void setIncludeSunPert(boolean includeSunPert)
    {
        this.includeSunPert = includeSunPert;
    }

    public boolean isIncludeSolRadPress()
    {
        return includeSolRadPress;
    }

    public void setIncludeSolRadPress(boolean includeSolRadPress)
    {
        this.includeSolRadPress = includeSolRadPress;
    }

    public boolean isIncludeAtmosDrag()
    {
        return includeAtmosDrag;
    }

    public void setIncludeAtmosDrag(boolean includeAtmosDrag)
    {
        this.includeAtmosDrag = includeAtmosDrag;
    }

    public double getMass()
    {
        return mass;
    }

    public void setMass(double mass)
    {
        this.mass = mass;
    }

    public double getArea()
    {
        return area;
    }

    public void setArea(double area)
    {
        this.area = area;
    }

    public double getCR()
    {
        return CR;
    }

    public void setCR(double CR)
    {
        this.CR = CR;
    }

    public double getCD()
    {
        return CD;
    }

    public void setCD(double CD)
    {
        this.CD = CD;
    }

    public double getStepSize()
    {
        return stepSize;
    }

    public void setStepSize(double stepSize)
    {
        this.stepSize = stepSize;
    }

    public double getMinStepSize()
    {
        return minStepSize;
    }

    public void setMinStepSize(double minStepSize)
    {
        this.minStepSize = minStepSize;
    }

    public double getMaxStepSize()
    {
        return maxStepSize;
    }

    public void setMaxStepSize(double maxStepSize)
    {
        this.maxStepSize = maxStepSize;
    }

    public double getRelAccuracy()
    {
        return relAccuracy;
    }

    public void setRelAccuracy(double relAccuracy)
    {
        this.relAccuracy = relAccuracy;
    }
    
    public double getPopogateTimeLen()
    {
        return popogateTimeLen;
    }

    public void setPopogateTimeLen(double popogateTimeLen)
    {
        this.popogateTimeLen = popogateTimeLen;
    }

    // ====================================================
    // =======  ORBIT Problem Functions (Deprecated) ======
    // ====================================================
    /**
     * Equations of Motion (accelerations) for 2-Body Problem + perturbations.
     * @deprecated Superseded by Orekit NumericalPropagator force models (HolmesFeatherstoneAttractionModel,
     *             ThirdBodyAttraction, SolarRadiationPressure, DragForce). Retained for OrbitProblem interface compatibility.
     * @param var state position
     * @param vel state velocity
     * @param t time
     * @return acceleration
     */
    @Deprecated
    public double[] deriv(double[] var, double[] vel, double t)
    {
        double[] acc = new double[3];
        double[][] E = new double[3][3];
        double[][] T = new double[3][3];

        // CAREFUL on time, should use TT then convert to UTC later or something??
        // otherwise UTC time is not uniform length??

        // prepare - Transformation matrix to body-fixed system
        //double Mjd_TT = Mjd0_TT + t / 86400.0; // mjd_tt = start epic
        double Mjd_TT = (JD_TT0 + t/86400.0) - AstroConst.JDminusMJD; // Convert sim time to JD to MJD
        
        // calculate current UT time from TT
        double Mjd_UT1 = Mjd_TT - Time.deltaT(Mjd_TT); //

        // careful if Mjd_TT > J2000.0 - should be take care of in PrecMatrix_Equ_Mjd
        // good use of PrecMatrix_Equ_Mjd - followed by nutation to get TOD
        T = MathUtils.mult(CoordinateConversion.NutMatrix(Mjd_TT), CoordinateConversion.PrecMatrix_Equ_Mjd(AstroConst.MJD_J2000, Mjd_TT));
        //E = CoordinateConversion.GHAMatrix(Mjd_UT1);
        E = MathUtils.mult(CoordinateConversion.GHAMatrix(Mjd_UT1), T);

        // Acceleration due to harmonic gravity field
        acc = GravityField.AccelHarmonic(var, E, AstroConst.GM_Earth, AstroConst.R_Earth, AstroConst.CS, n_max, m_max);

        // Luni-solar perturbations 
        double[] r_Sun = new double[3];
        if (includeSunPert || includeSolRadPress)
        {
            r_Sun = Sun.calculateSunPositionLowTT(Mjd_TT);
        }
        
        if (includeSunPert)
        {
            acc = MathUtils.add(acc, GravityField.AccelPointMass(var, r_Sun, AstroConst.GM_Sun));
        }

        double[] r_Moon = new double[3];
        if (includeLunarPert)
        {
            r_Moon = Moon.MoonPosition(Mjd_TT);
            acc = MathUtils.add(acc, GravityField.AccelPointMass(var, r_Moon, AstroConst.GM_Moon));
        }

        // Solar radiation pressure
        if (includeSolRadPress)
        {
            acc = MathUtils.add(acc, MathUtils.scale(Sun.AccelSolrad(var, r_Sun, area, mass, CR, AstroConst.P_Sol, AstroConst.AU), Sun.Illumination(var, r_Sun)));
        }

        // Atmospheric drag [uses, altitude]
        if (includeAtmosDrag)
        {
            acc = MathUtils.add(acc, Atmosphere.AccelDrag(Mjd_TT, var, vel, T, area, mass, CD));
        }

        return acc;

    } // deriv
    
    // verbose - debug
    private boolean verbose = false;
    public void setVerbose(boolean verbose){ this.verbose=verbose;}
    public boolean getVerbose(){ return verbose;}

    //public Vector getEphemerisVector();
    public void addState2Ephemeris(StateVector state)
    {
        // add new points to ephemeris converting back to TT (and skipping 0)
        if(! (state.state[0] == 0.0) )
        {
            state.state[0] = (JD_TT0 + state.state[0]/86400.0);
            ephemeris.add(state);
        }
    }
    
     // meant to be over ridden if there are any input vars
    public double getVar(int varInt)
    {
        double var = 0;
        
        switch(varInt)
        {
            case 0:
                var = popogateTimeLen;
                break;
            default:
                var = 0;
                break;
        }
        
        return var;
    }

    // meant to be over ridden if there are any input vars
    public void setVar(int varInt, double val)
    {
        switch(varInt)
        {
            case 0:
                popogateTimeLen = val;
                break;
            default:
                break;
        }
    }

    // meant to be over ridden if there are any input vars
    public Vector<InputVariable> getInputVarVector()
    {
        Vector<InputVariable> varVec = new Vector<InputVariable>(1);
        
        InputVariable inVar = new InputVariable(this, 0, varNames[0], popogateTimeLen);
        varVec.add(inVar);
        
        return varVec;
    }
    
    // meant to be over ridden if there are any input vars
    public Vector<GoalParameter> getGoalParamVector()
    {
        Vector<GoalParameter> varVec = new Vector<GoalParameter>(17);
        
        for (int i = 0; i < goalNames.length; i++)
        {
            GoalParameter inVar = new GoalParameter(this, i, goalNames[i], getGoal(i)); // hmm, need to put current value here if possible
            varVec.add(inVar);
        }
        
        return varVec;
    }

    // meant to be over ridden if there are any input vars
    public Double getGoal(int goalInt)
    {
        Double val = null;
        
        if(lastStateVector != null)
        {
            // calculate goals value
            switch(goalInt)
            {
                case 0: // X Position (J2000) [m]
                    val = lastStateVector.state[1];
                    break;
                case 1: // Y Position (J2000) [m]
                    val = lastStateVector.state[2];
                    break;
                case 2: // Z Position (J2000) [m]
                    val = lastStateVector.state[3];
                    break;
                case 3: // X Velocity (J2000) [m/s]
                    val = lastStateVector.state[4];
                    break;
                case 4: // Y Velocity (J2000) [m/s]
                    val = lastStateVector.state[5];
                    break;
                case 5: // Z Velocity (J2000) [m/s]
                    val = lastStateVector.state[6];
                    break;
                case 6: // Semimajor axis (Osculating) [m]
                    val = Kepler.SingularOsculatingElementsEarth(lastStateVector)[0];
                    break;
                case 7: // Eccentricity (Osculating)
                    val = Kepler.SingularOsculatingElementsEarth(lastStateVector)[1];
                    break;
                case 8: // Inclination (Osculating) [deg]
                    val = Kepler.SingularOsculatingElementsEarth(lastStateVector)[2] * 180.0/Math.PI;
                    break;
                case 9: // Longitude of the ascending node (Osculating) [deg]
                    val = Kepler.SingularOsculatingElementsEarth(lastStateVector)[3] * 180.0/Math.PI;
                    break;
                case 10: // Argument of pericenter (Osculating) [deg]
                    val = Kepler.SingularOsculatingElementsEarth(lastStateVector)[4] * 180.0/Math.PI;
                    break;
                case 11: // Mean anomaly (Osculating) [deg]
                    val = Kepler.SingularOsculatingElementsEarth(lastStateVector)[5] * 180.0/Math.PI;
                    break;
                case 12: // Orbital Radius [m]
                    val = Math.sqrt(Math.pow(lastStateVector.state[1], 2.0)+Math.pow(lastStateVector.state[2], 2.0)+Math.pow(lastStateVector.state[3], 2.0));
                    break;
                case 13: // Derivative of Orbital Radius [m/s]
                    // rdot = v dot R
                    double[] R = new double[] {lastStateVector.state[1],lastStateVector.state[2],lastStateVector.state[3]};
                    double normR = MathUtils.norm(R);
                    R = MathUtils.scale(R, 1.0/normR); // unit vector
                    
                    double[] v = new double[] {lastStateVector.state[4],lastStateVector.state[5],lastStateVector.state[6]};
                    double rDot = MathUtils.dot(v, R);
                    val = rDot;
                    break;
                case 14: // Latitude [deg]
                    // get current j2k pos
                    double[] currentJ2kPos = new double[]{lastStateVector.state[1], lastStateVector.state[2], lastStateVector.state[3]};
                    // mod pos -needs to be TEME of date
                    //double[] modPos = CoordinateConversion.EquatorialEquinoxFromJ2K(lastStateVector.state[0] - AstroConst.JDminusMJD, currentJ2kPos)
                    // teme pos
                    double[] temePos = CoordinateConversion.J2000toTEME(lastStateVector.state[0] - AstroConst.JDminusMJD, currentJ2kPos);
                    // lla  (submit time in UTC)
                    double deltaTT2UTC = Time.deltaT(lastStateVector.state[0] - AstroConst.JDminusMJD); // = TT - UTC
                    double[] lla = GeoFunctions.GeodeticLLA(temePos, lastStateVector.state[0] - AstroConst.JDminusMJD - deltaTT2UTC); // tt-UTC = deltaTT2UTC

                    val = lla[0] * 180.0 / Math.PI;
                    break;
                case 15: // Longitude [deg]
                    // get current j2k pos
                    currentJ2kPos = new double[]{lastStateVector.state[1], lastStateVector.state[2], lastStateVector.state[3]};
                    // mod pos - needs to be TEME of date
                    //modPos = CoordinateConversion.EquatorialEquinoxFromJ2K(lastStateVector.state[0] - AstroConst.JDminusMJD, currentJ2kPos)
                    // teme pos
                    temePos = CoordinateConversion.J2000toTEME(lastStateVector.state[0] - AstroConst.JDminusMJD, currentJ2kPos);
                    // lla  (submit time in UTC)
                    deltaTT2UTC = Time.deltaT(lastStateVector.state[0] - AstroConst.JDminusMJD); // = TT - UTC
                    lla = GeoFunctions.GeodeticLLA(temePos, lastStateVector.state[0] - AstroConst.JDminusMJD - deltaTT2UTC); // tt-UTC = deltaTT2UTC

                    val = lla[1] * 180.0 / Math.PI;
                    break;
                case 16: // Altitude [m]
                    // get current j2k pos
                    currentJ2kPos = new double[]{lastStateVector.state[1], lastStateVector.state[2], lastStateVector.state[3]};
                    // mod pos - needs to be TEME of date
                    //modPos = CoordinateConversion.EquatorialEquinoxFromJ2K(lastStateVector.state[0] - AstroConst.JDminusMJD, currentJ2kPos)
                    // teme pos
                    temePos = CoordinateConversion.J2000toTEME(lastStateVector.state[0] - AstroConst.JDminusMJD, currentJ2kPos);
                    // lla  (submit time in UTC)
                    deltaTT2UTC = Time.deltaT(lastStateVector.state[0] - AstroConst.JDminusMJD); // = TT - UTC
                    lla = GeoFunctions.GeodeticLLA(temePos, lastStateVector.state[0] - AstroConst.JDminusMJD - deltaTT2UTC); // tt-UTC = deltaTT2UTC

                    val = lla[2];
                    break;   
                    
                
            } // switch
        } // last state not null
        
        return val;
    } // getGoal   

    public boolean isStopOnApogee()
    {
        return stopOnApogee;
    }

    public void setStopOnApogee(boolean stopOnApogee)
    {
        this.stopOnApogee = stopOnApogee;
    }

    public boolean isStopOnPerigee()
    {
        return stopOnPerigee;
    }

    public void setStopOnPerigee(boolean stopOnPerigee)
    {
        this.stopOnPerigee = stopOnPerigee;
    }

    public boolean isStopOnAscendingNode()
    {
        return stopOnAscendingNode;
    }

    public void setStopOnAscendingNode(boolean stopOnAscendingNode)
    {
        this.stopOnAscendingNode = stopOnAscendingNode;
    }

    public boolean isStopOnDescendingNode()
    {
        return stopOnDescendingNode;
    }

    public void setStopOnDescendingNode(boolean stopOnDescendingNode)
    {
        this.stopOnDescendingNode = stopOnDescendingNode;
    }

    public boolean isStopOnAltitude()
    {
        return stopOnAltitude;
    }

    public void setStopOnAltitude(boolean stopOnAltitude)
    {
        this.stopOnAltitude = stopOnAltitude;
    }

    public double getStopAltitudeValue()
    {
        return stopAltitudeValue;
    }

    public void setStopAltitudeValue(double stopAltitudeValue)
    {
        this.stopAltitudeValue = stopAltitudeValue;
    }

}
