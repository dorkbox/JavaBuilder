package dorkbox.build;

import java.io.File;
import java.io.IOException;

import com.esotericsoftware.wildcard.Paths;

import dorkbox.Build;
import dorkbox.build.util.PreJarAction;
import dorkbox.build.util.jar.JarOptions;
import dorkbox.build.util.jar.JarSigner;
import dorkbox.build.util.jar.JarUtil;

public class Jarable {
    private ProjectJava projectJava;

    private boolean setDateLatest = false;

    private boolean includeSource = false;

    boolean includeSourceAsSeparate = false;

    /**
     * Sign the jar with a self-signed certificate
     */
    private boolean signJar;

    private String sigName;


    private Class<?> mainClass;
    private transient PreJarAction preJarAction;


    public Jarable(ProjectJava projectJava) {
        this.projectJava = projectJava;
    }

    /** Specify that all of the dates in the file should be now. */
    public Jarable setDateLatest() {
        this.setDateLatest = true;
        return this;
    }

    /** Should we include the source in the jar (alongside the class files)? */
    public Jarable includeSource() {
        this.includeSource = true;
        return this;
    }

    /**
     * When ALSO creating a source jar, this lets you override the NAME (either relative or absolute) for the source file
     * <p>
     * The default is xyz-src.zip
     */
    public Jarable sourceFilename(String sourceFilename) {
        this.projectJava.setSourceZipName(sourceFilename);
        return this;
    }

    /**
     * Sign the jar with a self-signed certificate
     */
    public Jarable sign() {
        this.signJar = true;
        return this;
    }

    /** What name will be used for signing the jar? */
    public Jarable signatureName(String sigName) {
        this.sigName = sigName;
        return this;
    }

    /**
     * Should we include a zip of source files NEXT to the jar?
     * <p>
     * IE: xyz.jar + xyz-src.zip
     */
    public Jarable includeSourceAsSeparate() {
        this.includeSourceAsSeparate = true;
        return this;
    }

    /** Actions that might need to take place before the project is jar'd */
    public Jarable preJarAction(PreJarAction preJarAction) {
        this.preJarAction = preJarAction;
        return this;
    }

    /** Specify the main class. */
    public Jarable mainClass(Class<?> clazz) {
        this.mainClass = clazz;
        return this;
    }

    /** Builds a jar from the specified source files, class file, and extras */
    void buildJar() throws IOException {
        if (this.preJarAction != null) {
            Build.log().message("Running action before Jar is created...");
            this.preJarAction.executeBeforeJarHappens(this.projectJava.stagingDir);
        }

        JarOptions jarOptions = new JarOptions();
        jarOptions.setDateLatest = this.setDateLatest;
        jarOptions.outputFile = this.projectJava.outputFile;
        jarOptions.inputPaths = new Paths(this.projectJava.stagingDir.getAbsolutePath());
        jarOptions.extraPaths = this.projectJava.extraFiles;
        if (this.mainClass != null) {
            jarOptions.mainClass = this.mainClass.getCanonicalName();
            jarOptions.classpath = this.projectJava.classPaths;
        }
        if (this.includeSource) {
            jarOptions.sourcePaths = this.projectJava.sourcePaths;
        }
        if (!this.projectJava.licenses.isEmpty()) {
            jarOptions.licenses = this.projectJava.licenses;
        }

        JarUtil.jar(jarOptions);

        if (this.includeSourceAsSeparate) {
            String name = this.projectJava.getSourceZipName();

            jarOptions = new JarOptions();
            jarOptions.setDateLatest = this.setDateLatest;
            jarOptions.outputFile = new File(name);
            jarOptions.extraPaths = this.projectJava.extraFiles;
            jarOptions.sourcePaths = this.projectJava.sourcePaths;
            if (!this.projectJava.licenses.isEmpty()) {
                jarOptions.licenses = this.projectJava.licenses;
            }

            JarUtil.zip(jarOptions);
        }

        if (this.signJar) {
            JarSigner.sign(this.projectJava.outputFile.getAbsolutePath(), this.sigName);
        }
    }

    public ProjectJava outputFile(String jarOutputFileName) {
        return this.projectJava.outputFile(jarOutputFileName);
    }
}
