package org.pepsoft.worldpainter.dynmap;

import org.pepsoft.util.Box;
import org.pepsoft.util.IconUtils;
import org.pepsoft.util.MathUtils;
import org.pepsoft.util.plugins.PluginManager;
import org.pepsoft.util.swing.ManagesScale;
import org.pepsoft.util.swing.TiledImageViewer;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.objects.WPObject;
import org.pepsoft.worldpainter.plugins.CustomObjectManager;
import org.pepsoft.worldpainter.plugins.WPPluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import javax.swing.*;
import javax.vecmath.Point3i;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.pepsoft.minecraft.Constants.DEFAULT_WATER_LEVEL;
import static org.pepsoft.worldpainter.DefaultPlugin.JAVA_ANVIL_1_15;
import static org.pepsoft.worldpainter.Dimension.Anchor.NORMAL_DETAIL;
import static org.pepsoft.worldpainter.plugins.WPPluginManager.DESCRIPTOR_PATH;

/**
 * A component which can show isometric 3D views of arbitrary {@link WPObject}s
 * using dynmap to render the view. The view can be zoomed and rotated using
 * mouse and keyboard.
 *
 * <p>Created by Pepijn Schmitz on 05-06-15.
 */
public class DynmapPreviewer extends TiledImageViewer implements ManagesScale {
    public DynmapPreviewer() {
        this(135.0, 60.0, 0);
    }

    public DynmapPreviewer(double myAzimuth, double myInclination, int myZoom) {
        super(true, false, 2);
        initialAzimuth = myAzimuth;
        initialInclination = myInclination;
        initialZoom = myZoom;
        azimuth = myAzimuth;
        inclination = myInclination;
        setZoom(myZoom);
        setFocusable(true);

        addMouseWheelListener(e -> {
            if (e.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL) {
                int newZoom = getZoom() - e.getWheelRotation();
                setZoom(Math.max(newZoom, -4));
            }
        });

        InputMap inputMap = getInputMap(JComponent.WHEN_FOCUSED);
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "rotateLeft");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "rotateRight");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "rotateUp");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "rotateDown");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0), "rotateLeft");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_D, 0), "rotateRight");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, 0), "rotateUp");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), "rotateDown");
        inputMap.put(KeyStroke.getKeyStroke('-'), "zoomOut");
        inputMap.put(KeyStroke.getKeyStroke('+'), "zoomIn");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), "reset");

        ActionMap actionMap = getActionMap();
        actionMap.put("rotateLeft", rotateLeft);
        actionMap.put("rotateRight", rotateRight);
        actionMap.put("rotateUp", rotateUp);
        actionMap.put("rotateDown", rotateDown);
        actionMap.put("zoomIn", zoomIn);
        actionMap.put("zoomOut", zoomOut);
        actionMap.put("reset", reset);

        setActionStates();

        JPanel buttonPanel = new JPanel(new GridLayout(2, 3, 2, 2));
        buttonPanel.setOpaque(false);

        buttonPanel.add(createActionButton(rotateLeft));
        buttonPanel.add(createActionButton(rotateUp));
        buttonPanel.add(createActionButton(rotateRight));

        buttonPanel.add(createActionButton(zoomIn));
        buttonPanel.add(createActionButton(rotateDown));
        buttonPanel.add(createActionButton(zoomOut));

        buttonPanel.setSize(buttonPanel.getPreferredSize());
        buttonPanel.setLocation(4, 4);
        add(buttonPanel);
    }

    public WPObject getObject() {
        return object;
    }

    public void setObject(WPObject object, Dimension dimension) {
        this.object = object;
        setActionStates();
        if (object != null) {
            // Make sure the object is prepared for export, in case it is from a
            // plugin and needs a mapping applied, for instance
            object.prepareForExport(dimension);
            WPObjectDynmapWorld dmWorld = new WPObjectDynmapWorld(object);
            tileProvider = new DynmapTileProvider(dmWorld);
            tileProvider.setAzimuth(azimuth);
            tileProvider.setInclination(inclination);
            tileProvider.setCaves(caves);
            setTileProvider(tileProvider);
        } else {
            tileProvider = null;
            if (getTileProviderCount() > 0) {
                removeAllTileProviders();
            }
        }
    }

    public double getAzimuth() {
        return azimuth;
    }

    public void setAzimuth(double azimuth) {
        this.azimuth = azimuth;
        setActionStates();
        if (tileProvider != null) {
            tileProvider.setAzimuth(azimuth);
        }
    }

    public double getInclination() {
        return inclination;
    }

    public void setInclination(double inclination) {
        this.inclination = inclination;
        setActionStates();
        if (tileProvider != null) {
            tileProvider.setInclination(inclination);
        }
    }

    public boolean isCaves() {
        return caves;
    }

    public void setCaves(boolean caves) {
        this.caves = caves;
        if (tileProvider != null) {
            tileProvider.setCaves(caves);
        }
    }

    public BufferedImage createImage() {
        Point3i offset = object.getOffset();
        Point3i dimensions = object.getDimensions();
        Rectangle tileCoords = tileProvider.getBounds(new Box(offset.x, offset.x + dimensions.x - 1, offset.y, offset.y + dimensions.y - 1, offset.z, offset.z + dimensions.z - 1));
        BufferedImage image = new BufferedImage(tileCoords.width * 128, tileCoords.height * 128, BufferedImage.TYPE_INT_ARGB);
        for (int dx = 0; dx < tileCoords.width; dx++) {
            for (int dy = 0; dy < tileCoords.height; dy++) {
                tileProvider.paintTile(image, tileCoords.x + dx, tileCoords.y + dy, dx * 128, dy * 128);
            }
        }
        return image;
    }

    private JButton createActionButton(Action action) {
        JButton button = new JButton(action);
        button.setHideActionText(true);
        button.setMargin(new Insets(0, 0, 0, 0));
        return button;
    }

    private void setActionStates() {
        zoomIn.setEnabled(object != null);
        zoomOut.setEnabled((object != null) && (getZoom() > -4));
        rotateLeft.setEnabled(object != null);
        rotateRight.setEnabled(object != null);
        rotateUp.setEnabled((object != null) && (inclination > 30.0));
        rotateDown.setEnabled((object != null) && (inclination < 90.0));
    }

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        // Install java.util.logging -> slf4j bridge:
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        // Load or initialise configuration
        Configuration config = Configuration.load(); // This will migrate the configuration directory if necessary
        if (config == null) {
            if (! logger.isDebugEnabled()) {
                // If debug logging is on, the Configuration constructor will
                // already log this
                logger.info("Creating new configuration");
            }
            config = new Configuration();
        }
        Configuration.setInstance(config);
        logger.info("Installation ID: " + config.getUuid());

        // Load and install trusted WorldPainter root certificate
        X509Certificate trustedCert = null;
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            trustedCert = (X509Certificate) certificateFactory.generateCertificate(DynmapPreviewer.class.getResourceAsStream("/wproot.pem"));
        } catch (CertificateException e) {
            logger.error("Certificate exception while loading trusted root certificate", e);
        }

        // Load the plugins
        if (trustedCert != null) {
            PluginManager.loadPlugins(new File(Configuration.getConfigDir(), "plugins"), trustedCert.getPublicKey(), DESCRIPTOR_PATH, Version.VERSION_OBJ, false);
        } else {
            logger.error("Trusted root certificate not available; not loading plugins");
        }
        WPPluginManager.initialise(config.getUuid(), WPContext.INSTANCE);

        JFrame frame = new JFrame("DynMapPreviewerTest");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        DynmapPreviewer viewer = new DynmapPreviewer();
        WPObject object = CustomObjectManager.getInstance().loadObject(new File(args[0]));
        TileFactory tileFactory = TileFactoryFactory.createNoiseTileFactory(0L, Terrain.GRASS, JAVA_ANVIL_1_15.minZ, JAVA_ANVIL_1_15.standardMaxHeight, 58, DEFAULT_WATER_LEVEL, false, true, 20.0f, 1.0);
        Dimension dimension = new World2(JAVA_ANVIL_1_15, 0L, tileFactory).getDimension(NORMAL_DETAIL);
        viewer.setObject(object, dimension);
        frame.getContentPane().add(viewer, BorderLayout.CENTER);
        frame.setSize(800, 600);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private final Action zoomIn = new AbstractAction("Zoom In", IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/magnifier_zoom_in.png")) {
            public void actionPerformed(ActionEvent e) {
                setZoom(getZoom() + 1);
                setActionStates();
            }
        };

    private final Action zoomOut = new AbstractAction("Zoom Out", IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/magnifier_zoom_out.png")) {
            public void actionPerformed(ActionEvent e) {
                if (getZoom() > -4) {
                    setZoom(getZoom() - 1);
                    setActionStates();
                }
            }
        };

    private final Action rotateLeft = new AbstractAction("Rotate Left", IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/arrow_left.png")) {
            public void actionPerformed(ActionEvent e) {
                azimuth = MathUtils.mod(azimuth - 15.0, 360.0);
                tileProvider.setAzimuth(azimuth);
                refresh(true);
            }
        };

    private final Action rotateRight = new AbstractAction("Rotate Right", IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/arrow_right.png")) {
            public void actionPerformed(ActionEvent e) {
                azimuth = MathUtils.mod(azimuth + 15.0, 360.0);
                tileProvider.setAzimuth(azimuth);
                refresh(true);
            }
        };

    private final Action rotateUp = new AbstractAction("Rotate Up", IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/arrow_up.png")) {
            public void actionPerformed(ActionEvent e) {
                double oldInclination = inclination;
                inclination = Math.max(inclination - 15.0, 30.0);
                if (inclination != oldInclination) {
                    setActionStates();
                    tileProvider.setInclination(inclination);
                    refresh(true);
                }
            }
        };

    private final Action rotateDown = new AbstractAction("Rotate Down", IconUtils.loadScaledIcon("org/pepsoft/worldpainter/icons/arrow_down.png")) {
            public void actionPerformed(ActionEvent e) {
                double oldInclination = inclination;
                inclination = Math.min(inclination + 15.0, 90.0);
                if (inclination != oldInclination) {
                    setActionStates();
                    tileProvider.setInclination(inclination);
                    refresh(true);
                }
            }
        };

    private final Action reset = new AbstractAction("Reset") {
            public void actionPerformed(ActionEvent e) {
                if ((azimuth != initialAzimuth) || (inclination != initialInclination) || (getZoom() != initialZoom)) {
                    if (azimuth != initialAzimuth) {
                        azimuth = initialAzimuth;
                        tileProvider.setAzimuth(azimuth);
                    }
                    if (inclination != initialInclination) {
                        inclination = initialInclination;
                        tileProvider.setInclination(inclination);
                    }
                    if (getZoom() != initialZoom) {
                        setZoom(initialZoom); // Will also refresh the view
                    } else {
                        refresh(true);
                    }
                    setActionStates();
                }
            }
        };

    private final double initialAzimuth, initialInclination;
    private final int initialZoom;
    private WPObject object;
    private DynmapTileProvider tileProvider;
    private double azimuth, inclination;
    private boolean caves;

    private static final Logger logger = LoggerFactory.getLogger(DynmapPreviewer.class);
}