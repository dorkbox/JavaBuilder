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
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.esotericsoftware.yamlbeans.YamlConfig;
import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlWriter;
import com.esotericsoftware.yamlbeans.scalar.ScalarSerializer;

import dorkbox.BuildVersion;
import dorkbox.Builder;
import dorkbox.build.util.BuildLog;
import dorkbox.build.util.CrossCompileClass;
import dorkbox.build.util.DependencyWalker;
import dorkbox.build.util.FileNotFoundRuntimeException;
import dorkbox.build.util.classloader.ByteClassloader;
import dorkbox.build.util.classloader.JavaMemFileManager;
import dorkbox.build.util.wildcard.Path;
import dorkbox.build.util.wildcard.Paths;
import dorkbox.license.License;
import dorkbox.license.LicenseType;
import dorkbox.util.FileUtil;
import dorkbox.util.OS;

@SuppressWarnings({"AccessStaticViaInstance", "Convert2Diamond"})
public
class ProjectJava extends Project<ProjectJava> {

    protected ArrayList<String> extraArgs;

    public Paths classPaths = new Paths();

    private transient ByteClassloader bytesClassloader = null;

    protected transient Jarable jarable = null;

    private boolean suppressSunWarnings = false;
    private final List<CrossCompileClass> crossCompileClasses = new ArrayList<CrossCompileClass>(4);

    Integer targetJavaVersion = null;
    private boolean isUploaded = false;

    public static
    ProjectJava create(String projectName) {
        ProjectJava project = new ProjectJava(projectName);
        Project.create(project);
        return project;
    }

    ProjectJava(String projectName) {
        super(projectName);
    }

    public
    ProjectJava classPath(ProjectJar project) {
        if (project == null) {
            throw new NullPointerException("Project cannot be null!");
        }

        Paths paths = new Paths();
        paths.addFile(project.outputFile.get().getAbsolutePath());
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

    public
    Jarable jar() {
        if (this.jarable == null) {
            this.jarable = new Jarable(this);
        }
        return this.jarable;
    }

    /**
     * Suppress sun warnings during the compile stage. ONLY enable this is you know what you are doing in your project!
     */
    public
    ProjectJava suppressSunWarnings() {
        this.suppressSunWarnings = true;
        return this;
    }

    /**
     * Specifies the version to compile to?
     * <p/>
     * IE: compile for 1.6 on JDK 1.8. When compiling for a different version, you have that version's 1.6 rt.jar
     *
     * @return true if this project was built, false otherwise
     */
    @Override
    public
    boolean build(final int targetJavaVersion) throws IOException {
        // we should always rebuild if specified
        forceRebuild |= buildOptions.compiler.forceRebuild;

        // save off the target version we build
        this.targetJavaVersion = targetJavaVersion;

        // check dependencies for this project
        resolveDependencies();

        // we should always rebuild if specified
        shouldBuild |= forceRebuild;

        shouldBuild |= hasDependenciesChanged();

        shouldBuild |= !verifyChecksums();


        // exit early if we already built this project, unless we force a rebuild
        if (!forceRebuild && buildList.contains(this.name)) {
            if (!this.isBuildingDependencies) {
                BuildLog.title("Building")
                        .println(this.name + " already built this run");
            }
            return true;
        }
        else {
            buildList.add(this.name);
        }

        BuildLog.start();


        String version = "";
        if (this.version != null) {
            this.version.verify();
            version = this.version.toString();
        }

        if (OS.javaVersion > targetJavaVersion) {
            BuildLog.title("Cross-Compile")
                    .println(this.name + " " + version + "  [Java v1." + targetJavaVersion + "]");
        }
        else {
            BuildLog.title("Compiling").println(this.name  + " " + version);
        }

        logDependencies();

        if (shouldBuild) {
            // We have to make sure that TEMPORARY projects are built - even if that temp project DOES NOT need to build b/c of source code
            // changes.
            for (Project<?> project : fullDependencyList) {
                // dep can be a jar as well (don't have to build a jar)
                if (!(project instanceof ProjectJar)) {
                    if (!buildList.contains(project.name)) {
                        // let the build itself determine if it needs to run,
                        boolean prev = project.isBuildingDependencies;
                        project.isBuildingDependencies = true;

                        boolean prevTemp = project.overrideTemporary;
                        project.overrideTemporary = true; // setting this to true will force a temp project to build

                        project.build(targetJavaVersion);

                        project.isBuildingDependencies = prev;

                        project.overrideTemporary = prevTemp;
                    }
                }
            }

            // barf if we don't have source files!
            if (this.sourcePaths.isEmpty()) {
                throw new IOException("No source files specified for project: " + this.name);
            }

            // make sure ALL dependencies are on the classpath.
            for (Project<?> project : fullDependencyList) {
                // dep can be a jar as well
                final File file = project.outputFile.get();

                if (!file.canRead()) {
                    // if this project is a jar project, there might be "extra files", which mean this project IS NOT a
                    // compile dependency, but a runtime dependency (via the "extra files" section)
                    Paths extraFiles = project.extraFiles();
                    if (extraFiles.size() == 0) {
                        throw new IOException("Dependency " + project + " for project '" + this.name + "' does not have a source jar or extra files. " +
                                              "Something is very wrong. A source jar is a compile dependency, and extra files are runtime" +
                                              " dependencies.");
                    } else {
                        // add the extra files to the classpath
                        this.classPaths.add(extraFiles);
                    }
                }
                else {
                    // if we are compiling our build instructions (and projects), this won't exist. This is OK,
                    // because we run from memory instead (in the classloader)
                    this.classPaths.addFile(file.getAbsolutePath());
                }
            }

            // add source class file dependencies
            if (!this.sourceDependencies.isEmpty()) {
                Map<File, String> relativeLocations = new HashMap<File, String>();

                Set<String> dependencies = new HashSet<String>();


                if (OS.javaVersion > targetJavaVersion) {
                    BuildLog.title("Cross-Compile")
                            .println("Class dependencies  [Java v1." + targetJavaVersion + "]");
                }
                else {
                    BuildLog.title("Compiling").println("Class dependencies");
                }

                List<File> files = sourceDependencies.getFiles();
                Collections.sort(files);

                for (File sourceFile : files) {
                    String relativeNameNoExtension = DependencyWalker.collect(sourceFile, dependencies);
                    relativeLocations.put(sourceFile, relativeNameNoExtension);

                    BuildLog.println(relativeNameNoExtension + ".java");
                }

                // have to compile these classes!
                BuildLog.disable();

                ProjectJava tempProject = ProjectJava.create("ClassFileDependencies:" + this.name)
                                                     .temporary()
                                                     .options(buildOptions)
                                                     .sourcePath(sourceDependencies);

                FileUtil.delete(tempProject.stagingDir);
                FileUtil.mkdir(tempProject.stagingDir);
                tempProject.shouldBuild = true; // always build temp projects

                try {
                    tempProject.build(this.targetJavaVersion);
                } catch (RuntimeException e) {
                    e.printStackTrace();
                }

                File buildLocation = new File(this.stagingDir.getParent(), "classFileDeps");
                FileUtil.delete(buildLocation);
                FileUtil.mkdir(buildLocation);

                // now have to save out the source files (that are now converted to .class files)
                // There can be inner-classes, so the ALL children of the parent dir must be added.
                files = FileUtil.parseDir(tempProject.stagingDir);
                int root = tempProject.stagingDir.getAbsolutePath().length() + 1; // include slash
                for (File file : files) {
                    String relativeName = file.getAbsolutePath().substring(root);
                    FileUtil.copyFile(file, new File(buildLocation, relativeName));
                }

                FileUtil.delete(tempProject.stagingDir);
                BuildLog.enable();

                // now have to add this dir to our project
                this.classPaths.addFile(buildLocation.getAbsolutePath());
            }


            // have to build cross-compiled files first.
            File crossCompatBuiltFile = null;
            if (!crossCompileClasses.isEmpty()) {
                BuildLog.println();

                crossCompatBuiltFile = new File(this.stagingDir.getParent(), "crossCompileBuilt");
                FileUtil.delete(crossCompatBuiltFile);
                FileUtil.mkdir(crossCompatBuiltFile);

                Map<File, String> relativeLocations = new HashMap<File, String>();
                for (CrossCompileClass crossCompileClass : crossCompileClasses) {
                    Paths sourceFiles = crossCompileClass.sourceFiles;

                    if (OS.javaVersion > targetJavaVersion && targetJavaVersion < crossCompileClass.targetJavaVersion) {
                        Set<String> dependencies = new HashSet<String>();

                        for (File sourceFile : sourceFiles.getFiles()) {
                            BuildLog.title("Cross-Compile").println(sourceFile.getName() + "  [Java v1." +
                                                                    crossCompileClass.targetJavaVersion + "]");

                            String relativeNameNoExtension = DependencyWalker.collect(sourceFile, dependencies);
                            relativeLocations.put(sourceFile, relativeNameNoExtension);
                        }

                        BuildLog.disable();

                        Paths tempSource = new Paths();
                        for (String dependency : dependencies) {
                            tempSource.addFile(dependency);
                        }


                        ProjectJava tempProject = ProjectJava.create("CrossCompileClasses")
                                                             .temporary()
                                                             .options(buildOptions)
                                                             .sourcePath(tempSource)
                                                             .sourcePath(sourceFiles);

                        FileUtil.delete(tempProject.stagingDir);
                        FileUtil.mkdir(tempProject.stagingDir);
                        tempProject.forceRebuild(); // always build temp projects

                        try {
                            tempProject.build(crossCompileClass.targetJavaVersion);
                        } catch (RuntimeException e) {
                            e.printStackTrace();
                        }

                        // now have to save out the source files (that are now converted to .class files)
                        for (File sourceFile : sourceFiles.getFiles()) {
                            String s = relativeLocations.get(sourceFile) + ".class";
                            File file = new File(tempProject.stagingDir, s);
                            FileUtil.copyFile(file, new File(crossCompatBuiltFile, s));
                        }

                        // tempProject.cleanup();
                        // DO NOT want to call project.cleanup()!!
                        FileUtil.delete(tempProject.stagingDir);
                        BuildLog.enable();
                    }
                }

                // now have to add this dir to our project
                this.classPaths.addFile(crossCompatBuiltFile.getAbsolutePath());

                Paths crossIncludeFiles = new Paths();
                for (String relativeName : relativeLocations.values()) {
                    crossIncludeFiles.add(crossCompatBuiltFile.getAbsolutePath(), relativeName + ".class");

                }
                // now that this file is compiled, we add it to our "extra files" to be bundled up when we make the jar
                extraFiles(crossIncludeFiles);


                // have to remove all the (now cross compiled) java files since we don't want to build them with the "normal" build process
                Iterator<Path> iterator = sourcePaths.get()
                                                      .iterator();
                while (iterator.hasNext()) {
                    final Path path = iterator.next();
                    File file = path.file();

                    if (relativeLocations.containsKey(file)) {
                        iterator.remove();
                    }
                }
            }
            // done with extra-file cross-compile
            BuildLog.println();



            if (this.bytesClassloader == null && this.jarable == null) {
                FileUtil.delete(this.stagingDir);
            }


            runCompile(targetJavaVersion);
            BuildLog.println("Compile success");

            if (!temporary && this.version != null) {
                // only save the version info + files if we are NOT temporary
                // update the version BEFORE creating the jar!
                this.version.save();
            }

            if (this.jarable != null) {
                this.jarable.buildJar();
            }

            // calculate the hash of all the files in the source path
            saveChecksums();

            // save our dependencies and their version info
            saveDependencyVersionInfo();

            if (crossCompatBuiltFile != null) {
                FileUtil.delete(crossCompatBuiltFile);
            }

            BuildLog.println();
            BuildLog.title("Staging").println(this.stagingDir);
        }
        else {
            skippedBuild = true;
            BuildLog.println().println("Skipped (nothing changed)");
        }

        BuildLog.finish();

        return shouldBuild;
    }

    /**
     * @return true if our dependencies have changed and we need to rebuild
     */
    private
    boolean hasDependenciesChanged() throws IOException {
        boolean shouldBuild = false;

        // we want to make sure that we build IF one of our dependencies needs to build too
        for (Project<?> project : fullDependencyList) {
            if (!shouldBuild && !(project instanceof ProjectJar)) {
                // if one of our dependencies has to build, so do we (don't keep checking if we have to build)
                // also, we DO NOT check jar versions/etc here (that happens later)

                // if true, this means that the files ARE the same and they have not changed
                final boolean b = project.hash.verifyChecksums();
                shouldBuild = !b;
            }
        }

        // has our dependencies or their versions changed at all?
        final ArrayList<String> depsWithVersionInfo = new ArrayList<String>(fullDependencyList.size());
        for (Project<?> project : fullDependencyList) {
            // version can be null, which is OK for our tests here
            depsWithVersionInfo.add(project.name + ":" + project.version);
        }

        final String origDepsWithVersion = Builder.settings.get(this.name + ":deps", String.class);
        shouldBuild |= !depsWithVersionInfo.toString().equals(origDepsWithVersion);

        return shouldBuild;
    }

    /**
     * Compiles into class files.
     */
    private synchronized
    void runCompile(final int targetJavaVersion) throws IOException {
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
            BuildLog.println("Adding debug info");

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

        if (OS.javaVersion > targetJavaVersion) {
            // if our runtime env. is NOT equal to our target env.
            args.add("-source");
            args.add("1." + targetJavaVersion);

            args.add("-target");
            args.add("1." + targetJavaVersion);

            args.add("-bootclasspath");
            String location = this.buildOptions.compiler.crossCompileLibrary.getCrossCompileLibraryLocation(targetJavaVersion);
            File file = FileUtil.normalize(location);

            if (!file.canRead()) {
                throw new FileNotFoundRuntimeException("Unable to read cross compile jar: " + file.getAbsolutePath());
            }

            args.add(file.getAbsolutePath());
        }

        // suppress sun proprietary warnings
        if (this.suppressSunWarnings) {
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
            // System.err.println("CP " + this.classPaths.toString(File.pathSeparator));
            StringBuilder cp = new StringBuilder(this.classPaths.toString(File.pathSeparator));
            String javaLibPath = System.getProperty("java.home") + File.separator + "lib" + File.separator;

            // have to try to load the JCE to the classpath (it is not always included)
            // can't compile binaries that use the JCE otherwise.
            cp.append(File.pathSeparator + javaLibPath + "jce.jar");

            if (OS.javaVersion == 7) {
                // we have to add javaFX to the classpath (they are not included on the classpath by default), otherwise we
                // can't compile javaFX binaries. This was fixed in Java 1.8.
                cp.append(File.pathSeparator + System.getProperty("java.home") + File.separator + "lib" + File.separator + "jfxrt.jar");
            }

            args.add(cp.toString());
        }

        // now compile the code
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        JavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);

        PrintStream error = System.err;
        PrintStream out = System.out;
        final StringBuilder errorsDuringCompile = new StringBuilder();
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

            // redirect OUT/ERR
            PrintStream nullErrorStream = new PrintStream(new OutputStream() {
                @Override
                public
                void write(int b) throws IOException {
                    errorsDuringCompile.append((char)b);
                }
            });

            System.setErr(nullErrorStream);
            System.setOut(nullErrorStream);

            compiler.getTask(null, fileManager, diagnostics, args, null, javaFileObjectsFromFiles).call();
        } finally {
            try {
                fileManager.close();
            } catch (Exception e) {
                e.printStackTrace();
            }

            System.setErr(error);
            System.setOut(out);
        }

        if (errorsDuringCompile.length() > 0) {
            int length = errorsDuringCompile.length() - 1;
            if (errorsDuringCompile.charAt(length) == '\n') {
                errorsDuringCompile.deleteCharAt(length);
                length = errorsDuringCompile.length() - 1;

                if (errorsDuringCompile.charAt(length) == '\r') {
                    errorsDuringCompile.deleteCharAt(length);
                }
            }
            RuntimeException runtimeException = new RuntimeException("Compilation error: " + errorsDuringCompile.toString());
            runtimeException.setStackTrace(new StackTraceElement[0]);
            throw runtimeException;
        }

        boolean hasError = false;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR) {
                hasError = true;
                break;
            }
        }
        if (hasError) {
            final String temp = FileUtil.tempDirectory("temp");
            final File tempFile = new File(temp);
            String tempDir = tempFile.getParent();
            if (!tempFile.delete()) {
                BuildLog.println("Error deleting temp directory!!", tempDir);
            }

            BuildLog.enable();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                final String message = diagnostic.getMessage(null);
                final Diagnostic.Kind kind = diagnostic.getKind();

                if (this.suppressSunWarnings &&
                    (kind == Diagnostic.Kind.WARNING || kind == Diagnostic.Kind.MANDATORY_WARNING) &&
                    message.startsWith("sun.")) {
                    // skip all sun.XYZ warnings
                    continue;
                }

                BuildLog.start();
                BuildLog.title(kind.toString()).println(message);

                final JavaFileObject source = diagnostic.getSource();
                String className = null;

                if (source != null) {
                    final String name = source.getName();

                    // source.getName() : /tmp/Builder2506000973028434501.tmp/dorkbox/util/FileUtil.java
                    // we want:  dorkbox.util.FileUtil

                    // source.getName() can ALSO be the full path to the class file.

                    if (name.startsWith(tempDir)) {
                        final int lastIndexOf = name.lastIndexOf(".tmp");

                        if (lastIndexOf > 0) {
                            String croppedName = name.substring(lastIndexOf + 5);

                            className = croppedName.replace(File.separatorChar, '.').substring(0, croppedName.lastIndexOf('.'));
                            className += ":" + diagnostic.getLineNumber();
                        }
                    }

                    if (className == null) {
                        BuildLog.title("Location").println(getLineInfo(diagnostic));
                        className = name;
                    }
                }


                if (className != null) {
                    BuildLog.println(className);
                }
                else {
                    BuildLog.title("Location").println(getLineInfo(diagnostic));
                    BuildLog.println("Unknown location");
                }
                BuildLog.finish();
            }
            BuildLog.disable();

            RuntimeException runtimeException = new RuntimeException("Compilation errors");
            runtimeException.setStackTrace(new StackTraceElement[0]);
            throw runtimeException;
        }

        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
    }

    private static
    String getLineInfo(final Diagnostic<? extends JavaFileObject> diagnostic) {
        if (diagnostic.getLineNumber() > 0) {
            return  "Line: " + Long.toString(diagnostic.getLineNumber()) + ":" + Long.toString(diagnostic.getColumnNumber());
        } else {
            return "Unknown line";
        }
    }

    @Override
    public
    String getExtension() {
        return Project.JAR_EXTENSION;
    }


    /**
     * Specifies different source files to be cross compiled to a different version of java. This is useful when building
     * libraries that use runtime/etc features that have changed over time
     * </p>
     * The way this operates, is that is builds this file + any dependency (recursively auto-detected) to the target java version.
     * </p>
     * Next, it then removes ALL other files except this ones specified, and then it includes these files as a classpath to the compiler
     * for the primary build.
     * </p>
     * IMPORTANT!! - this can only auto-detect dependencies that are under the same root path - meaning NO jars, external classes, etc.
     *
     * @param sourceFiles The source files MUST be the FULL NAME (but not the path!) of the java file
     */
    public
    ProjectJava crossBuild(final int targetJavaVersion, final Paths sourceFiles) {
        crossCompileClasses.add(new CrossCompileClass(targetJavaVersion, sourceFiles));
        return this;
    }

    public
    interface OnJarEntryAction {
        boolean canHandle(String fileName);

        int onEntry(String fileName, ByteArrayInputStream inputStream, OutputStream output) throws Exception;
    }

    /**
     * Take all of the parameters of this project, and convert it to a text file.
     */
    @Override
    public
    void save(final String location) {
        try {
            YamlWriter writer = new YamlWriter(new FileWriter(location));
            YamlConfig config = writer.getConfig();

            config.writeConfig.setWriteRootTags(false);

            config.setPropertyElementType(ProjectJava.class, "licenses", License.class);
            config.setPrivateFields(true);

            config.readConfig.setConstructorParameters(License.class,
                                                       new Class[] {String.class, LicenseType.class},
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
        } catch (IOException e) {
            throw new RuntimeException("Unable to save file.", e);
        }
    }

    /**
     * The specified byte loading classloader to save the compiled class bytes into,
     */
    public
    ProjectJava compilerClassloader(ByteClassloader bytesClassloader) {
        this.bytesClassloader = bytesClassloader;
        return this;
    }

    @Override
    public
    ProjectJava version(BuildVersion version) {
        super.version(version);
        return this;
    }

    @Override
    public
    void uploadToMaven() throws IOException {
        // only if we save the build. Test builds don't save, and we shouldn't upload them to maven
        final boolean export = buildOptions.compiler.saveBuild;
        exportToMaven = export;

        if (!skippedBuild) {
            for (Project project : fullDependencyList) {
                project.exportToMaven = export;

                // dep can be a jar as well (don't upload dependency jars)
                if (project instanceof ProjectJava) {
                    project.uploadToMaven();
                }
            }

            if (this.mavenExporter != null) {
                this.mavenExporter.setProject(this);

                if (!isUploaded) {
                    // mark that we have been uploaded already, so we can only do it once per project
                    isUploaded = true;

                    this.mavenExporter.export();
                }
            }
        }
    }

    /**
     * @return true if the checksums for path match the saved checksums.  If there is a JAR file, it also checks to see if it is built &
     * matches the saved checksums.  If it's a temp project (and specifies a jar) the jarChecksum is ignored (so only checksums based on source code changes)
     */
    boolean verifyChecksums() throws IOException {
        // if temporary + we override the status, we ALWAYS build it
        if (this.temporary && this.overrideTemporary) {
            return false;
        }

        boolean sourceHashesSame = hash.verifyChecksums();
        if (!sourceHashesSame) {
            return false;
        }

        // if we have no jar file (and the sources are the same) OR we are temporary + don't override, it will have a jar, but won't save it
        if (this.jarable == null || (this.temporary && !this.overrideTemporary)) {
            return true;
        }

        // when we verify checksums, we verify the ORIGINAL (if there is version info) -- and when we SAVE checksums, we save the NEW version
        final File originalOutputFile = this.outputFile.getOriginal();

        if (originalOutputFile.canRead()) {
            String jarChecksum = hash.generateChecksum(originalOutputFile);
            String checkContents = Builder.settings.get(this.name + ":" + originalOutputFile.getAbsolutePath(), String.class);

            boolean outputFileGood = jarChecksum != null && jarChecksum.equals(checkContents);

            if (outputFileGood) {
                if (!this.jarable.includeSourceAsSeparate) {
                    return true;
                }
                else {
                    final File originalOutputFileSource = this.outputFile.getSourceOriginal();

                    // now check the src.zip file (if there was one).
                    jarChecksum = hash.generateChecksum(originalOutputFileSource);
                    checkContents = Builder.settings.get(this.name + ":" + originalOutputFileSource.getAbsolutePath(), String.class);

                    return jarChecksum != null && jarChecksum.equals(checkContents);
                }
            }
        }
        else {
            // output file was removed
            BuildLog.println("Output file was removed.");
            return false;
        }

        return true;
    }



    /**
     * Saves the checksums for a given path - PER PROJECT (otherwise updating a jar in one place, and saving it's checksum, will verify
     * it everywhere else)
     */
    void saveChecksums() throws IOException {
        // by default, we save the build. When building a 'test' build, we opt to NOT save the build hashes, so that a 'normal' build
        // will then compile.
        if (!buildOptions.compiler.saveBuild) {
            return;
        }

        hash.saveChecksums();

        // when we verify checksums, we verify the ORIGINAL (if there is version info) -- and when we SAVE checksums, we save the NEW version
        final File currentOutputFile = this.outputFile.get();

        // hash/save the jar file (if there was one)
        if (currentOutputFile.exists()) {
            String fileChecksum = hash.generateChecksum(currentOutputFile);
            Builder.settings.save(this.name + ":" + currentOutputFile.getAbsolutePath(), fileChecksum);

            if (this.jarable != null && this.jarable.includeSourceAsSeparate) {
                final File currentOutputFileSource = this.outputFile.getSource();

                // now check the src.zip file (if there was one).
                fileChecksum = hash.generateChecksum(currentOutputFileSource);

                Builder.settings.save(this.name + ":" + currentOutputFileSource.getAbsolutePath(), fileChecksum);
            }
        }
    }

    /**
     * Saves the dependency + version info (so if the version changes, we update the info)
     */
    private
    void saveDependencyVersionInfo() {
        final ArrayList<String> depsWithVersionInfo = new ArrayList<String>(fullDependencyList.size());
        for (Project<?> project : fullDependencyList) {
            // version can be null, which is OK for our tests here
            depsWithVersionInfo.add(project.name + ":" + project.version);
        }

        Builder.settings.save(this.name + ":deps", depsWithVersionInfo.toString());
    }
}
