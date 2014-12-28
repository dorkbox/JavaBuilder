import dorkbox.Build;

// setup source directories
public class Dirs {
    public static final String Dependencies = Build.path(BuildStrings.ProjectPath.Resources, "Dependencies");

    public static final String JavaTar = mkDir("javatar");
    public static final String YAML = mkDir("yaml");
    public static final String WildCard = mkDir("wildcard");
    public static final String LzmaJava = mkDir("lzma-java");

    public static final String Java_Redist = Build.path(BuildStrings.ProjectPath.Resources, "Java_Redist");
    public static final String OpenJDK_Runtime = Build.path(Java_Redist, "openJDK_runtime");

    public static String mkDir(String dir) {
        return Build.path(Dependencies, dir);
    }
}
