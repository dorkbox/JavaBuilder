/*
 * Copyright 2012 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.build;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.esotericsoftware.wildcard.Paths;
import com.esotericsoftware.yamlbeans.YamlConfig;
import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlWriter;
import com.esotericsoftware.yamlbeans.scalar.ScalarSerializer;

import dorkbox.Build;
import dorkbox.BuildOptions;
import dorkbox.build.util.PreJarAction;
import dorkbox.build.util.classloader.ByteClassloader;
import dorkbox.build.util.classloader.JavaMemFileManager;
import dorkbox.build.util.jar.JarOptions;
import dorkbox.build.util.jar.JarSigner;
import dorkbox.build.util.jar.JarUtil;
import dorkbox.license.License;
import dorkbox.license.LicenseType;
import dorkbox.util.FileUtil;
import dorkbox.util.OS;

public class ProjectJava extends ProjectBasics {

    protected ArrayList<String> extraArgs;

    protected Paths sourcePaths = new Paths();
    public Paths classPaths = new Paths();
    private boolean includeSource;
    public List<License> licenses = new ArrayList<License>();

    private transient PreJarAction preJarAction;

    private Class<?> mainClass;

    private ByteClassloader bytesClassloader = null;

    /**
     * Create an "anonymous" project. This project will ALWAYS be built, and will NOT save it's checksums
     */
    public static ProjectJava create() {
        ProjectJava create = create(Long.toString(System.currentTimeMillis()));
        create.saveChecksums = true;
        return create;
    }

    public static ProjectJava create(String projectName) {
        ProjectJava project = new ProjectJava(projectName);
        deps.put(projectName, project);

        return project;
    }

    public ProjectJava(String projectName) {
        super(projectName);

        checksum(this.sourcePaths);
        checksum(this.classPaths);
    }

    public ProjectJava sourcePath(Paths sourcePaths) {
        if (sourcePaths == null) {
            throw new NullPointerException("Source paths cannot be null!");
        }
        this.sourcePaths.add(sourcePaths);

        // ALWAYS add the source paths to be checksumed!
        checksum(sourcePaths);

        return this;
    }

    public ProjectJava sourcePath(String srcDir) {
        if (srcDir.endsWith("src")) {
            String parent = new File(srcDir).getAbsoluteFile().getParent();
            checksum(new Paths(parent));
        }

        return sourcePath(new Paths(srcDir, "./"));
    }

    public ProjectJava sourcePath(String dir, String... patterns) {
        return sourcePath(new Paths(dir, patterns));
    }

    public ProjectJava classPath(Paths classPaths) {
        if (classPaths == null) {
            throw new NullPointerException("Class paths cannot be null!");
        }

        this.classPaths.add(classPaths);

        return this;
    }

    public ProjectJava classPath(String dir, String... patterns) {
        return classPath(new Paths(dir, patterns));
    }

    /** extra files to include when you jar the project */
    @Override
    public ProjectJava extraFiles(Paths filePaths) {
        super.extraFiles(filePaths);
        return this;
    }

    @Override
    public final ProjectJava depends(String dependsProjectName) {
        super.depends(dependsProjectName);
        return this;
    }


    @Override
    public ProjectJava outputFile(String outputFileName) {
        super.outputFile(outputFileName);
        return this;
    }

    public ProjectJava addArg(String arg) {
        if (this.extraArgs == null) {
            this.extraArgs = new ArrayList<String>();
        }
        this.extraArgs.add(arg);

        return this;
    }

    @Override
    public ProjectJava build(BuildOptions options) throws Exception {
        //  (and add them to the classpath)
        Build.log().message();
        if (this.bytesClassloader == null) {
            if (options.compiler.jar.buildJar) {
                Build.log().title("Building").message(this.name, "Output - " + this.outputFile);
            } else {
                Build.log().title("Building").message(this.name, "Output - " + this.outputDir);
            }
        } else {
            Build.log().title("Building").message(this.name);
        }

        // exit early if we already built this project
        if (checkAndBuildDependencies(options)) {
            return this;
        }

        boolean shouldBuild = !verifyChecksums(options);

        if (shouldBuild) {
            // barf if we don't have source files!
            if (this.sourcePaths.getFiles().isEmpty()) {
                throw new RuntimeException("No source files specified for project: " + this.name);
            }

            // make sure our dependencies are on the classpath.
            if (this.dependencies != null) {
                for (String dep : this.dependencies) {
                    ProjectBasics project = deps.get(dep);
                    if (!project.outputFile.canRead()) {
                        throw new IOException("Dependency for project :" + this.name + " does not exist. '" + project.outputFile.getAbsolutePath() + "'");
                    }
                    // if we are compiling our build instructions (and projects), this won't exist. This is OK,
                    // because we run from memory instead (in the classloader)
                    this.classPaths.addFile(project.outputFile.getAbsolutePath());
                }
            }

            compile(options);
            Build.log().message("Compile success.");

            if (options.compiler.jar.buildJar) {
                if (this.preJarAction != null) {
                    Build.log().message("Running action before Jar is created...");
                    this.preJarAction.executeBeforeJarHappens(this.outputDir);
                }

                JarOptions jarOptions = new JarOptions();
                jarOptions.outputFile = this.outputFile;
                jarOptions.inputPaths = new Paths(this.outputDir.getAbsolutePath());
                jarOptions.extraPaths = this.extraFiles;
                if (this.mainClass != null) {
                    jarOptions.mainClass = this.mainClass.getCanonicalName();
                    jarOptions.classpath = this.classPaths;
                }
                if (this.includeSource) {
                    jarOptions.sourcePaths = this.sourcePaths;
                }
                if (!this.licenses.isEmpty()) {
                    jarOptions.licenses = this.licenses;
                }
                jarOptions.createDebugVersion = options.compiler.debugEnabled;



                JarUtil.jar(jarOptions);

                if (options.compiler.jar.signJar) {
                    JarSigner.sign(this.outputFile.getAbsolutePath(), options.compiler.jar.signName);
                }

                // calculate the hash of all the files in the source path
                saveChecksums();
            }
        } else {
            Build.log().message("Skipped (nothing changed)");
        }
        if (shouldBuild && options.compiler.deleteOnComplete) {
            FileUtil.delete(this.outputDir);
        }

        return this;
    }

    /**
     * @return true if the checksums for path match the saved checksums and the jar file exists
     */
    @Override
    boolean verifyChecksums(BuildOptions options) throws IOException {
        boolean sourceHashesSame = super.verifyChecksums(options);
        if (!sourceHashesSame) {
            return false;
        }

        // if the sources are the same, check the jar file
        if (this.outputFile.exists()) {
            String jarChecksum = generateChecksum(this.outputFile);
            String checkContents = Build.settings.get(this.outputFile.getAbsolutePath(), String.class);

            return jarChecksum != null && jarChecksum.equals(checkContents);
        } else {
            // output file was removed
            return false;
        }
    }

    /**
     * Saves the checksums for a given path
     */
    @Override
    void saveChecksums() throws IOException {
        super.saveChecksums();

        // hash/save the jar file (if there was one)
        if (this.saveChecksums && this.outputFile.exists()) {
            String fileChecksum = generateChecksum(this.outputFile);
            Build.settings.save(this.outputFile.getAbsolutePath(), fileChecksum);
        }
    }

    /**
     * Compiles into class files.
     */
    public synchronized void compile(BuildOptions buildOptions) throws IOException {
        // if you get messages, such as
        // warning: [path] bad path element "/x/y/z/lib/fubar-all.jar": no such file or directory
        //   That is because that file exists in a MANIFEST.MF somewhere on the classpath! Find the jar that has that, and rip out
        //   the offending manifest.mf file.
        // see: http://stackoverflow.com/questions/1344202/bad-path-warning-where-is-it-coming-from

        if (this.sourcePaths.isEmpty()) {
            throw new IOException("No source files found.");
        }

        ArrayList<String> args = new ArrayList<String>();
        if (buildOptions.compiler.enableCompilerTrace) {
            // TODO: Interesting to note, that when COMPILING this with verbose, we can get a list (from the compiler) of EVERY CLASS NEEDED
            //         to run our application! This would be useful in "trimming" the necessary files needed by the JVM.
            args.add("-verbose");
        }

        if (buildOptions.compiler.debugEnabled) {
            Build.log().message("Adding debug info.");

            args.add("-g"); // Generate all debugging information, including local variables. By default, only line number and source file information is generated.
        } else {
            args.add("-g:none");
        }

        if (this.bytesClassloader == null) {
            // we only want to use an output directory if we have output!
            FileUtil.delete(this.outputDir);
            FileUtil.mkdir(this.outputDir);

            args.add("-d");
            args.add(this.outputDir.getAbsolutePath());
        }

        args.add("-encoding");
        args.add("UTF-8");

        if (OS.getJavaVersion() > buildOptions.compiler.targetJavaVersion) {
            Build.log().message("Building cross-platform target!");
            // if our runtime env. is NOT equal to our target env.
            args.add("-source");
            args.add(buildOptions.getTargetVersion());

            args.add("-target");
            args.add(buildOptions.getTargetVersion());

            args.add("-bootclasspath");
            args.add(buildOptions.compiler.crossCompileLibrary.getCrossCompileLibraryLocation(buildOptions.compiler.targetJavaVersion));
        }

        // suppress sun proprietary warnings
        if (buildOptions.compiler.suppressSunWarnings) {
            args.add("-XDignore.symbol.file");
        }

        if (this.extraArgs != null) {
            boolean extraArgsHaveXlint = false;
            for (String arg : this.extraArgs) {
                if (arg.startsWith("-Xlint")) {
                    extraArgsHaveXlint = true;
                    break;
                }
            }

            if (!extraArgsHaveXlint) {
                args.add("-Xlint:all");
            }

            // add any extra arguments
            args.addAll(this.extraArgs);
        }


        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("No compiler available. Ensure you are running from a JDK, and not a JRE.");
        }

        if (this.classPaths != null && !this.classPaths.isEmpty()) {
            args.add("-classpath");
            args.add(this.classPaths.toString(File.pathSeparator));
        }

        // now compile the code
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        JavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);

        try {
            Iterable<? extends JavaFileObject> javaFileObjectsFromFiles;
            if (this.bytesClassloader == null) {
                javaFileObjectsFromFiles = ((StandardJavaFileManager)fileManager).getJavaFileObjectsFromFiles(this.sourcePaths.getFiles());
            } else {
                fileManager = new JavaMemFileManager((StandardJavaFileManager)fileManager, this.bytesClassloader);
                ((JavaMemFileManager)fileManager).setSource(this.sourcePaths);
                javaFileObjectsFromFiles = ((JavaMemFileManager)fileManager).getSourceFiles();
            }

            compiler.getTask(null, fileManager, diagnostics, args, null,
                             javaFileObjectsFromFiles).call();
        } finally {
            fileManager.close();
        }


        boolean hasError = false;
        for (@SuppressWarnings("rawtypes") Diagnostic diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR) {
                hasError = true;
                break;
            }
        }
        if (hasError) {
            StringBuilder buffer = new StringBuilder(1024);
            for (@SuppressWarnings("rawtypes") Diagnostic diagnostic : diagnostics.getDiagnostics()) {
                if (buffer.length() > 0) {
                    buffer.append("\n");
                }
                buffer.append("Line ").append(diagnostic.getLineNumber()).append(": ");
                buffer.append(diagnostic.getMessage(null));
            }
            throw new RuntimeException("Compilation errors:\n" + buffer);
        }

        compiler = null;
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException ex) {
        }
    }

    @Override
    public String getExtension() {
        return ".jar";
    }

    public static interface OnJarEntryAction {
        boolean canHandle(String fileName);
        int onEntry(String fileName, ByteArrayInputStream inputStream, OutputStream output) throws Exception;
    }

    public ProjectJava includeSourceInJar() {
        this.includeSource = true;
        return this;
    }

    public ProjectJava license(License license) {
        this.licenses.add(license);
        return this;
    }

    public ProjectJava license(List<License> licenses) {
        this.licenses.addAll(licenses);
        return this;
    }

    /** Actions that might need to take place before the project is jar'd */
    public ProjectJava preJarAction(PreJarAction preJarAction) {
        this.preJarAction = preJarAction;
        return this;
    }

    /**
     * Specify the main class.
     */
    public ProjectJava mainClass(Class<?> clazz) {
        this.mainClass = clazz;
        return this;
    }

    /**
     * Take all of the parameters of this project, and convert it to a text file.
     * @throws IOException
     */
    public void toBuildFile() throws IOException {
        YamlWriter writer = new YamlWriter(new FileWriter("build.oak"));
        YamlConfig config = writer.getConfig();

        config.writeConfig.setWriteRootTags(false);

        config.setPropertyElementType(ProjectJava.class, "licenses", License.class);
        config.setPrivateFields(true);

        config.readConfig.setConstructorParameters(License.class, new Class[]{String.class, LicenseType.class}, new String[] {"licenseName", "licenseType"});
        config.readConfig.setConstructorParameters(ProjectJava.class, new Class[]{String.class}, new String[] {"projectName"});

        config.setScalarSerializer(Paths.class, new ScalarSerializer<Paths>() {
            @Override
            public Paths read (String value) throws YamlException {
                String[] split = value.split(File.pathSeparator);
                Paths paths = new Paths();
                for (String s : split) {
                    paths.addFile(s);
                }
                return paths;
            }

            @Override
            public String write (Paths paths) throws YamlException {
                return paths.toString(File.pathSeparator);
            }
        });

        writer.write(this);
        writer.close();
    }

    /**
     * The specified byte loading classloader to save the compiled class bytes into,
     */
    public ProjectJava compilerClassloader(ByteClassloader bytesClassloader) {
        this.bytesClassloader = bytesClassloader;
        return this;
    }
}