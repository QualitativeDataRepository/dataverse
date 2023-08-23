package edu.harvard.iq.dataverse.util;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.engine.command.DataverseRequest;
import edu.harvard.iq.dataverse.engine.command.impl.AbstractSubmitToArchiveCommand;
import edu.harvard.iq.dataverse.settings.JvmSettings;
import edu.harvard.iq.dataverse.settings.SettingsServiceBean;
import io.gdcc.spi.export.Exporter;

/**
 * Simple class to reflectively get an instance of the desired class for
 * archiving.
 * 
 */
public class ArchiverUtil {

    private static final Logger logger = Logger.getLogger(ArchiverUtil.class.getName());

    public ArchiverUtil() {
    }

    public static Class getSubmitToArchiveCommandClass(String className, DatasetVersion version) {
        if (className != null) {
            try {

                /*
                 * Step 1 - find the EXPORTERS dir and add all jar files there to a class loader
                 */
                List<URL> jarUrls = new ArrayList<>();
                Optional<String> exportPathSetting = JvmSettings.ARCHIVERS_DIRECTORY.lookupOptional(String.class);
                if (exportPathSetting.isPresent()) {
                    Path exporterDir = Paths.get(exportPathSetting.get());
                    // Get all JAR files from the configured directory
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(exporterDir, "*.jar")) {
                        // Using the foreach loop here to enable catching the URI/URL exceptions
                        for (Path path : stream) {
                            logger.log(Level.FINE, "Adding {0}", path.toUri().toURL());
                            // This is the syntax required to indicate a jar file from which classes should
                            // be loaded (versus a class file).
                            jarUrls.add(new URL("jar:" + path.toUri().toURL() + "!/"));
                        }
                    } catch (IOException e) {
                        logger.warning("Problem accessing external Archivers: " + e.getLocalizedMessage());
                    }
                }
                // Assumes version has the base classLoader
                URLClassLoader cl = URLClassLoader.newInstance(jarUrls.toArray(new URL[0]), version.getClass().getClassLoader());

                Class<?> clazz = Class.forName(className, true, cl);
                return clazz;
            } catch (Exception e) {
                logger.warning("Unable to get class for an Archiver: " + className);
                e.printStackTrace();
            }
        }
        return null;
    }

    public static AbstractSubmitToArchiveCommand createSubmitToArchiveCommand(String className, DataverseRequest dvr, DatasetVersion version) {
        Class<?> clazz = getSubmitToArchiveCommandClass(className, version);
        if (clazz != null) {
            try {
                if (AbstractSubmitToArchiveCommand.class.isAssignableFrom(clazz)) {
                    Constructor<?> ctor;
                    ctor = clazz.getConstructor(DataverseRequest.class, DatasetVersion.class);
                    return (AbstractSubmitToArchiveCommand) ctor.newInstance(new Object[] { dvr, version });
                }
            } catch (Exception e) {
                logger.warning("Unable to instantiate an Archiver of class: " + className);
                e.printStackTrace();
            }
        }
        return null;
    }
    
    public static boolean onlySingleVersionArchiving(Class<? extends AbstractSubmitToArchiveCommand> clazz, SettingsServiceBean settingsService) {
        Method m;
        try {
            m = clazz.getMethod("isSingleVersion", SettingsServiceBean.class);
            Object[] params = { settingsService };
            return (Boolean) m.invoke(null, params);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (SecurityException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return (AbstractSubmitToArchiveCommand.isSingleVersion(settingsService));
    }

    public static boolean isSomeVersionArchived(Dataset dataset) {
        boolean someVersionArchived = false;
        for (DatasetVersion dv : dataset.getVersions()) {
            if (dv.getArchivalCopyLocation() != null) {
                someVersionArchived = true;
                break;
            }
        }

        return someVersionArchived;
    }

}