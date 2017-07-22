package dorkbox.build;

import java.io.File;
import java.io.IOException;

import com.esotericsoftware.wildcard.Paths;

import dorkbox.build.util.BuildLog;
import dorkbox.build.util.PreJarAction;
import dorkbox.build.util.jar.JarOptions;
import dorkbox.build.util.jar.JarSigner;
import dorkbox.build.util.jar.JarUtil;

@SuppressWarnings("unused")
public
class Jarable {
    private final ProjectJava projectJava;

    private long overrideDate = -1;

    private boolean includeSource = false;

    boolean includeSourceAsSeparate = false;

    private boolean includeLicenseInfo = true;

    /**
     * Sign the jar with a self-signed certificate
     */
    private boolean signJar;

    private String sigName;


    private String mainClass;
    private transient PreJarAction preJarAction;
    private Paths newClassPath;


    public
    Jarable(ProjectJava projectJava) {
        this.projectJava = projectJava;
    }

    /**
     * Specify that all of the dates in the file should the Build date (which can be overridden)
     */
    public
    Jarable overrideDate(long time) {
        this.overrideDate = time;
        return this;
    }

    /**
     * Should we include the source in the jar (alongside the class files)?
     */
    public
    Jarable includeSource() {
        this.includeSource = true;
        return this;
    }

    public
    Jarable overrideClassPath(final Paths newClassPath) {
        this.newClassPath = newClassPath;
        return this;
    }

    /**
     * Sign the jar with a self-signed certificate
     */
    public
    Jarable sign() {
        this.signJar = true;
        return this;
    }

    /**
     * What name will be used for signing the jar?
     */
    public
    Jarable signatureName(String sigName) {
        this.sigName = sigName;
        return this;
    }

    /**
     * Should we include a zip of source files NEXT to the jar?
     * <p/>
     * IE: xyz.jar + xyz-src.zip
     */
    public
    Jarable includeSourceAsSeparate() {
        this.includeSourceAsSeparate = true;
        return this;
    }

    /**
     * Actions that might need to take place before the project is jar'd
     */
    public
    Jarable preJarAction(PreJarAction preJarAction) {
        this.preJarAction = preJarAction;
        return this;
    }

    /**
     * Specify the main class.
     */
    public
    Jarable mainClass(Class<?> clazz) {
        this.mainClass = clazz.getCanonicalName();
        return this;
    }

    /**
     * Specify the main class.
     */
    public
    Jarable mainClass(String className) {
        this.mainClass = className;
        return this;
    }

    /**
     * Skips installing the license info in the resulting jar. By DEFAULT, license info is included.
     * <p>
     * The source code file will ALWAYS have the license information included
     */
    public
    Jarable skipLicenseInfo() {
        includeLicenseInfo = false;
        return this;
    }

    /**
     * Builds a jar from the specified source files, class file, and extras
     */
    void buildJar() throws IOException {

        if (this.preJarAction != null) {
            BuildLog.start();

            //noinspection AccessStaticViaInstance
            BuildLog.title("Pre-Jar action").println();
            this.preJarAction.executeBeforeJarHappens(this.projectJava.stagingDir);

            BuildLog.finish();
        }

        JarOptions jarOptions = new JarOptions();
        jarOptions.overrideDate = this.overrideDate;
        jarOptions.outputFile = this.projectJava.outputFile.get();
        jarOptions.inputPaths = new Paths(this.projectJava.stagingDir.getAbsolutePath());
        jarOptions.extraPaths = this.projectJava.extraFiles;


        if (this.mainClass != null) {
            jarOptions.mainClass = this.mainClass;
            jarOptions.classpath = this.projectJava.classPaths;
        }
        if (this.includeSource) {
            jarOptions.sourcePaths = this.projectJava.sourcePaths;
        }
        if (includeLicenseInfo && !this.projectJava.licenses.isEmpty()) {
            jarOptions.licenses = this.projectJava.licenses;
        }
        if (this.newClassPath != null) {
            jarOptions.classpath = this.newClassPath;
        }

        JarUtil.jar(jarOptions);

        if (this.includeSourceAsSeparate) {
            jarOptions = new JarOptions();
            jarOptions.overrideDate = this.overrideDate;
            jarOptions.outputFile = this.projectJava.outputFile.getSource();
            jarOptions.extraPaths = this.projectJava.extraFiles;
            jarOptions.sourcePaths = this.projectJava.sourcePaths;


            if (!this.projectJava.licenses.isEmpty()) {
                // the source code will ALWAYS have the license information included
                jarOptions.licenses = this.projectJava.licenses;
            }

            JarUtil.zip(jarOptions);
        }

        if (this.signJar) {
            JarSigner.sign(this.projectJava.outputFile.get().getAbsolutePath(), this.sigName);
        }
    }

    /**
     * If the specified file is ONLY a filename, then it (and the source file, if necessary) will be placed into the staging directory.
     * If a path + name is specified, then they will be placed as is.
     * <p>
     * If no extension is provide, the default is '.jar'
     */    public
    ProjectJava outputFile(String jarOutputFileName) {
        return this.projectJava.outputFile(jarOutputFileName);
    }

    /**
     * If the specified file is ONLY a filename, then it (and the source file, if necessary) will be placed into the staging directory.
     * If a path + name is specified, then they will be placed as is.
     * <p>
     * If no extension is provide, the default is '.jar'
     */
    public
    ProjectJava outputFile(File jarOutputFileName) {
        return this.projectJava.outputFile(jarOutputFileName);
    }

    public
    ProjectJava asProject() {
        return this.projectJava;
    }
}
