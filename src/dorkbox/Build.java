package dorkbox;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.security.CodeSource;
import java.security.ProtectionDomain;


@SuppressWarnings("resource")
public
class Build {

    // ALSO copied over to javabuilder build dir (as an example), so make sure to update in both locations
    // ~/dorkbox/eclipse/jre/bin/java -jar dist/JavaBuilder_v1.3.jar build javabuilder
    // ~/dorkbox/eclipse/jre/bin/java -Xrunjdwp:transport=dt_socket,server=y,address=1044 -jar dist/JavaBuilder_v1.3.jar build javabuilder


    static {
        File oak = get();
        if (oak != null) {
            // we want to look for the libraries, because they are OFTEN going to be in the incorrect path.
            // this is only necessary if they aren't correctly loaded.
            try {
                Class.forName("com.esotericsoftware.wildcard.Paths");
            } catch (Exception e) {
                // whoops. can't find it on the path

                File libDir = new File(oak.getParentFile(), "libs");
                if (!libDir.isDirectory()) {
                    libDir = new File(oak.getParentFile().getParentFile(), "libs");
                }

                if (!libDir.isDirectory()) {
                    throw new RuntimeException("Unable to find the libs directory for execution: " + oak);
                }

                Class<?>[] parameters = new Class[] {URL.class};
                Class<URLClassLoader> sysclass = URLClassLoader.class;
                URLClassLoader sysloader = (URLClassLoader) ClassLoader.getSystemClassLoader();

                try {
                    Method method = sysclass.getDeclaredMethod("addURL", parameters);
                    method.setAccessible(true);

                    // add lib dir jars
                    for (File f : libDir.listFiles()) {
                        if (!f.isDirectory() && f.canRead() && f.getName().endsWith(".jar")) {
                            method.invoke(sysloader, new Object[] {f.toURI().toURL()});
                        }
                    }

                    // now have to add fastMD5 sum jars
                    libDir = new File(libDir, "fast-md5");
                    for (File f : libDir.listFiles()) {
                        if (!f.isDirectory() && f.canRead() && f.getName().endsWith(".jar")) {
//                            System.err.println("adding url " + f.getAbsolutePath());
                            method.invoke(sysloader, new Object[] {f.toURI().toURL()});
                        }
                    }
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
            return new File(fileName).getAbsoluteFile().getCanonicalFile();

        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Unable to decode file path!", e);
        } catch (IOException e) {
            throw new RuntimeException("Unable to get canonical file path!", e);
        }
    }

    /**
     * Gets the version number.
     */
    public static
    String getVersion() {
        return "2.20";
    }

    public static
    void main(String... _args) throws Exception {
        // now startup like normal
        Builder.start(_args);
    }
}

