package dorkbox;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.security.CodeSource;
import java.security.ProtectionDomain;

import dorkbox.util.FileUtil;
import dorkbox.util.OS;
import dorkbox.util.Version;


@SuppressWarnings("resource")
public
class Build {

    // ALSO copied over to javabuilder build dir (as an example), so make sure to update in both locations
    // ~/dorkbox/eclipse/jre/bin/java -jar dist/JavaBuilder_v1.3.jar build javabuilder
    // ~/dorkbox/eclipse/jre/bin/java -Xrunjdwp:transport=dt_socket,server=y,address=1044 -jar dist/JavaBuilder_v1.3.jar build javabuilder


    static {
        try {
            // we have to add javaFX to the classpath (they are not included on the classpath by default), otherwise we
            // can't compile javaFX binaries. This was fixed in Java 1.8.
            if (OS.javaVersion == 7) {

                Class<?>[] parameters = new Class[] {URL.class};
                Class<URLClassLoader> sysclass = URLClassLoader.class;
                URLClassLoader sysloader = (URLClassLoader) ClassLoader.getSystemClassLoader();

                Method method = sysclass.getDeclaredMethod("addURL", parameters);
                method.setAccessible(true);

                File javaFxLib = new File(System.getProperty("java.home") + File.separator + "lib" + File.separator + "jfxrt.jar");
                method.invoke(sysloader, new Object[] {javaFxLib.toURI().toURL()});
                //System.err.println("adding url " + javaFxLib.getAbsolutePath());
            }
        } catch (Exception ignored) {
            System.err.println("CRITICAL:: Can't load javaFX to the classpath URLS");
        }


        File runLocation = get();
        if (runLocation != null) {
            // we want to look for the libraries, because they are OFTEN going to be in the incorrect path.
            // this is only necessary if they aren't correctly loaded.
            try {
                Class.forName("com.esotericsoftware.wildcard.Paths");
            } catch (Exception e) {
                // whoops. can't find it on the path

                File parent = runLocation.getParentFile();
                File libDir = new File(parent, "libs");

                if (!libDir.isDirectory()) {
                    libDir = new File(parent.getParentFile(), "libs");
                }

                if (!libDir.isDirectory()) {
                    throw new RuntimeException("Unable to find the libs directory for execution: " + runLocation);
                }

                Class<?>[] parameters = new Class[] {URL.class};
                Class<URLClassLoader> sysclass = URLClassLoader.class;
                URLClassLoader sysloader = (URLClassLoader) ClassLoader.getSystemClassLoader();

                try {
                    Method method = sysclass.getDeclaredMethod("addURL", parameters);
                    method.setAccessible(true);

                    // add lib dir jars
                    for (File f : libDir.listFiles()) {
                        final String name = f.getName();
                        if (!f.isDirectory() && f.canRead() && name.endsWith(".jar") && !name.contains("source") && !name.contains("src")) {
                            // System.err.println("adding url " + f.getAbsolutePath());
                            method.invoke(sysloader, new Object[] {f.toURI().toURL()});
                        }
                    }

                    // now have to add fastMD5 sum jars (they are in a different location)
                    libDir = new File(libDir, "fast-md5");
                    for (File f : libDir.listFiles()) {
                        final String name = f.getName();

                        if (!f.isDirectory() && f.canRead() && name.endsWith(".jar") && !name.contains("source") && !name.contains("src")) {
                            // System.err.println("adding url " + f.getAbsolutePath());
                            method.invoke(sysloader, new Object[] {f.toURI().toURL()});
                        }
                    }

                    // try to load the library again to make sure the libs loaded
                    Class.forName("com.esotericsoftware.wildcard.Paths");
                } catch (Throwable t) {
                    t.printStackTrace();
                    throw new RuntimeException("Unable to load the libs directory for execution: " + libDir);
                }
            }
        }
    }

    public static
    File get() {
        return get(Build.class);
    }

    /**
     * Retrieve the location that this classfile was loaded from, or possibly null if the class was compiled on the fly
     */
    @SuppressWarnings("Duplicates")
    public static
    File get(Class<?> clazz) {
        // Get the location of this class
        ProtectionDomain pDomain = clazz.getProtectionDomain();
        CodeSource cSource = pDomain.getCodeSource();

        // file:/X:/workspace/XYZ/classes/  when it's in ide/flat
        // jar:/X:/workspace/XYZ/jarname.jar  when it's jar
        URL loc = cSource.getLocation();

        // we don't always have a protection domain (for example, when we compile classes on the fly, from memory)
        if (loc == null) {
            return null;
        }

        // Can have %20 as spaces (in winxp at least). need to convert to proper path from URL
        try {
            String fileName = URLDecoder.decode(loc.getFile(), "UTF-8");
            return FileUtil.normalize(fileName);

        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Unable to decode file path!", e);
        }
    }

    /**
     * Gets the version number.
     */
    public static
    Version getVersion() {
        return new Version("3.2");
    }

    public static
    void main(String... _args) throws Exception {
        // now startup like normal
        Builder.start(_args);
    }
}

