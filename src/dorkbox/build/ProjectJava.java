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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import dorkbox.build.util.BuildLog;
import dorkbox.build.util.classloader.ByteClassloader;
import dorkbox.build.util.classloader.JavaMemFileManager;
import dorkbox.license.License;
import dorkbox.license.LicenseType;
import dorkbox.util.FileUtil;
import dorkbox.util.OS;

public
class ProjectJava extends Project<ProjectJava> {

    protected ArrayList<String> extraArgs;

    protected Paths sourcePaths = new Paths();
    public Paths classPaths = new Paths();

    private ByteClassloader bytesClassloader = null;

    private Jarable jarable = null;

    public static
    ProjectJava create(String projectName) {
        ProjectJava project = new ProjectJava(projectName);
        deps.put(projectName, project);

        return project;
    }

    private
    ProjectJava(String projectName) {
        super(projectName);
    }

    public
    ProjectJava sourcePath(Paths sourcePaths) {
        if (sourcePaths == null) {
            throw new NullPointerException("Source paths cannot be null!");
        }
        this.sourcePaths.add(sourcePaths);

        // ALWAYS add the source paths to be checksumed!
        checksum(sourcePaths);

        return this;
    }

    public
    ProjectJava sourcePath(String srcDir) {
        if (srcDir.endsWith("src")) {
            String parent = new File(srcDir).getAbsoluteFile().getParent();
            checksum(new Paths(parent));
        }

        return sourcePath(new Paths(srcDir, "./"));
    }

    public
    ProjectJava sourcePath(String dir, String... patterns) {
        return sourcePath(new Paths(dir, patterns));
    }

    public
    ProjectJava classPath(ProjectJar project) {
        if (project == null) {
            throw new NullPointerException("Project cannot be null!");
        }

        Paths paths = new Paths();
        paths.addFile(project.outputFile.getAbsolutePath());
        this.classPaths.add(paths);

        return this;
    }

    public
    ProjectJava classPath(Paths classPaths) {
        if (classPaths == null) {
            throw new NullPointerException("Class paths cannot be null!");
        }

        this.classPaths.add(classPaths);

        return this;
    }

    public
    ProjectJava classPath(String dir, String... patterns) {
        if (new File(dir).isFile() && (patterns == null || patterns.length == 0)) {
            Paths paths = new Paths();
            paths.addFile(dir);
            return classPath(paths);
        }
        return classPath(new Paths(dir, patterns));
    }


    public
    ProjectJava addArg(String arg) {
        if (this.extraArgs == null) {
            this.extraArgs = new ArrayList<String>();
        }
        this.extraArgs.add(arg);

        return this;
    }


    @Override
    public
    void build() throws IOException {
        // exit early if we already built this project
        if (checkAndBuildDependencies()) {
            return;
        }

        Build.log().println();
        if (this.bytesClassloader == null && this.jarable == null) {
            Build.log().title("Building").println(this.name, "Output - " + this.stagingDir);
        }
        else {
            Build.log().title("Building").println(this.name);
        }

        boolean shouldBuild = !verifyChecksums();
        if (shouldBuild) {
            // barf if we don't have source files!
            if (this.sourcePaths.getFiles().isEmpty()) {
                throw new IOException("No source files specified for project: " + this.name);
            }

            // make sure ALL dependencies are on the classpath.
            Set<Project<?>> depends = new HashSet<Project<?>>(this.dependencies);
            getRecursiveDependencies(depends);

            for (Project<?> project : depends) {
                // dep can be a jar as well
                if (!project.outputFile.canRead()) {
                    throw new IOException(
                                    "Dependency for project :" + this.name + " does not exist. '" + project.outputFile.getAbsolutePath() +
                                    "'");
                }
                // if we are compiling our build instructions (and projects), this won't exist. This is OK,
                // because we run from memory instead (in the classloader)
                this.classPaths.addFile(project.outputFile.getAbsolutePath());
            }

            runCompile();
            Build.log().println("Compile success");

            if (this.jarable != null) {
                this.jarable.buildJar();
            }

            // calculate the hash of all the files in the source path
            saveChecksums();

            FileUtil.delete(this.stagingDir);
        }
        else {
            Build.log().println("Skipped (nothing changed)");
        }

        buildList.add(this.name);
    }

    public
    Jarable jar() {
        if (this.jarable == null) {
            this.jarable = new Jarable(this);
        }
        return this.jarable;
    }

    /**
     * @return true if the checksums for path match the saved checksums and the jar file exists
     */
    @Override
    boolean verifyChecksums() throws IOException {
        boolean sourceHashesSame = super.verifyChecksums();
        if (!sourceHashesSame) {
            return false;
        }

        // if the sources are the same, check the jar file
        if (this.jarable != null && this.outputFile.canRead()) {
            String jarChecksum = generateChecksum(this.outputFile);
            String checkContents = Build.settings.get(this.outputFile.getAbsolutePath(), String.class);

            boolean outputFileGood = jarChecksum != null && jarChecksum.equals(checkContents);

            if (outputFileGood) {
                if (!this.jarable.includeSourceAsSeparate) {
                    return true;
                }
                else {
                    // now check the src.zip file (if there was one).
                    jarChecksum = generateChecksum(this.outputFileSource);
                    checkContents = Build.settings.get(this.outputFileSource.getAbsolutePath(), String.class);

                    return jarChecksum != null && jarChecksum.equals(checkContents);
                }
            }
        }
        // output file was removed
        return false;
    }

    /**
     * Saves the checksums for a given path
     */
    @Override
    void saveChecksums() throws IOException {
        super.saveChecksums();

        // hash/save the jar file (if there was one)
        if (this.outputFile.exists()) {
            String fileChecksum = generateChecksum(this.outputFile);
            Build.settings.save(this.outputFile.getAbsolutePath(), fileChecksum);

            if (this.jarable != null && this.jarable.includeSourceAsSeparate) {
                // now check the src.zip file (if there was one).
                fileChecksum = generateChecksum(this.outputFileSource);

                Build.settings.save(this.outputFileSource.getAbsolutePath(), fileChecksum);
            }
        }
    }

    /**
     * Compiles into class files.
     */
    private synchronized
    void runCompile() throws IOException {
        // if you get messages, such as
        // warning: [path] bad path element "/x/y/z/lib/fubar-all.jar": no such file or directory
        //   That is because that file exists in a MANIFEST.MF somewhere on the classpath! Find the jar that has that, and rip out
        //   the offending manifest.mf file.
        // see: http://stackoverflow.com/questions/1344202/bad-path-warning-where-is-it-coming-from

        if (this.sourcePaths.isEmpty()) {
            throw new IOException("No source files found.");
        }

        ArrayList<String> args = new ArrayList<String>();
        if (this.buildOptions.compiler.enableCompilerTrace) {
            // TODO: Interesting to note, that when COMPILING this with verbose, we can get a list (from the compiler) of EVERY CLASS NEEDED
            //         to run our application! This would be useful in "trimming" the necessary files needed by the JVM.
            args.add("-verbose");
        }

        if (this.buildOptions.compiler.debugEnabled) {
            Build.log().println("Adding debug info");

            args.add("-g"); // Generate all debugging information, including local variables. By default, only line number and source file information is generated.
        }
        else {
            args.add("-g:none");
        }

        if (this.bytesClassloader == null) {
            // we only want to use an output directory if we have output!
            FileUtil.delete(this.stagingDir);
            FileUtil.mkdir(this.stagingDir);

            args.add("-d");
            args.add(this.stagingDir.getAbsolutePath());
        }

        args.add("-encoding");
        args.add("UTF-8");

        if (OS.getJavaVersion() > this.buildOptions.compiler.targetJavaVersion) {
            Build.log().println("Building cross-platform target for version: " + this.buildOptions.compiler.targetJavaVersion);
            // if our runtime env. is NOT equal to our target env.
            args.add("-source");
            args.add("1." + this.buildOptions.compiler.targetJavaVersion);

            args.add("-target");
            args.add("1." + this.buildOptions.compiler.targetJavaVersion);

            args.add("-bootclasspath");
            args.add(this.buildOptions.compiler.crossCompileLibrary.getCrossCompileLibraryLocation(
                            this.buildOptions.compiler.targetJavaVersion));
        }

        // suppress sun proprietary warnings
        if (this.buildOptions.compiler.suppressSunWarnings) {
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
                javaFileObjectsFromFiles = ((StandardJavaFileManager) fileManager).getJavaFileObjectsFromFiles(this.sourcePaths.getFiles());
            }
            else {
                fileManager = new JavaMemFileManager((StandardJavaFileManager) fileManager, this.bytesClassloader);
                ((JavaMemFileManager) fileManager).setSource(this.sourcePaths);
                javaFileObjectsFromFiles = ((JavaMemFileManager) fileManager).getSourceFiles();
            }

            compiler.getTask(null, fileManager, diagnostics, args, null, javaFileObjectsFromFiles).call();
        } finally {
            fileManager.close();
        }


        boolean hasError = false;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR) {
                hasError = true;
                break;
            }
        }
        if (hasError) {
            BuildLog.enable();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                BuildLog log = BuildLog.start();
                log.title(diagnostic.getKind().toString()).println(diagnostic.getMessage(null));

                String info = "Line: " + Long.toString(diagnostic.getLineNumber()) + ":" + Long.toString(diagnostic.getColumnNumber());
                log.title("Location").println(info);

                final JavaFileObject source = diagnostic.getSource();
                if (source != null) {
                    log.println(source.getName());
                } else {
                    log.println("Unknown location");
                }
                BuildLog.finish();
            }
            BuildLog.disable();
//            throw new RuntimeException("Compilation errors:\n" + buffer);
            RuntimeException runtimeException = new RuntimeException("Compilation errors");
            runtimeException.setStackTrace(new StackTraceElement[0]);
            throw runtimeException;
        }

        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException ex) {
        }
    }

    @Override
    public
    String getExtension() {
        return Project.JAR_EXTENSION;
    }

    public static
    interface OnJarEntryAction {
        boolean canHandle(String fileName);

        int onEntry(String fileName, ByteArrayInputStream inputStream, OutputStream output) throws Exception;
    }

    /**
     * Take all of the parameters of this project, and convert it to a text file.
     *
     * @throws IOException
     */
    public
    void toBuildFile() throws IOException {
        YamlWriter writer = new YamlWriter(new FileWriter("build.oak"));
        YamlConfig config = writer.getConfig();

        config.writeConfig.setWriteRootTags(false);

        config.setPropertyElementType(ProjectJava.class, "licenses", License.class);
        config.setPrivateFields(true);

        config.readConfig.setConstructorParameters(License.class, new Class[] {String.class, LicenseType.class},
                                                   new String[] {"licenseName", "licenseType"});
        config.readConfig.setConstructorParameters(ProjectJava.class, new Class[] {String.class}, new String[] {"projectName"});

        config.setScalarSerializer(Paths.class, new ScalarSerializer<Paths>() {
            @Override
            public
            Paths read(String value) throws YamlException {
                String[] split = value.split(File.pathSeparator);
                Paths paths = new Paths();
                for (String s : split) {
                    paths.addFile(s);
                }
                return paths;
            }

            @Override
            public
            String write(Paths paths) throws YamlException {
                return paths.toString(File.pathSeparator);
            }
        });

        writer.write(this);
        writer.close();
    }

    /**
     * The specified byte loading classloader to save the compiled class bytes into,
     */
    public
    ProjectJava compilerClassloader(ByteClassloader bytesClassloader) {
        this.bytesClassloader = bytesClassloader;
        return this;
    }

    public
    List<License> getLicenses() {
        return this.licenses;
    }

    @Override
    public
    ProjectJava version(String versionString) {
        super.version(versionString);
        return this;
    }
}
