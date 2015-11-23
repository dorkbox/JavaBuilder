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

import com.esotericsoftware.wildcard.Paths;
import com.esotericsoftware.yamlbeans.YamlConfig;
import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlWriter;
import com.esotericsoftware.yamlbeans.scalar.ScalarSerializer;
import dorkbox.Builder;
import dorkbox.build.util.BuildLog;
import dorkbox.build.util.CrossCompileClass;
import dorkbox.build.util.DependencyWalker;
import dorkbox.build.util.classloader.ByteClassloader;
import dorkbox.build.util.classloader.JavaMemFileManager;
import dorkbox.license.License;
import dorkbox.license.LicenseType;
import dorkbox.util.FileUtil;
import dorkbox.util.OS;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("unused")
public
class ProjectJava extends Project<ProjectJava> {

    protected ArrayList<String> extraArgs;

    protected Paths sourcePaths = new Paths();
    public Paths classPaths = new Paths();

    private ByteClassloader bytesClassloader = null;

    private Jarable jarable = null;

    private boolean suppressSunWarnings = false;
    private final List<CrossCompileClass> crossCompileClasses = new ArrayList<CrossCompileClass>(4);


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

    /**
     * Add paths to the list of sources that are available when compiling the code
     */
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

    /**
     * Add paths to the list of sources that are available when compiling the code
     */
    public
    ProjectJava sourcePath(String srcDir) {
        if (srcDir.endsWith("src")) {
            String parent = new File(srcDir).getAbsoluteFile().getParent();
            checksum(new Paths(parent));
        }

        return sourcePath(new Paths(srcDir, "./"));
    }

    /**
     * Add paths to the list of sources that are available when compiling the code
     */
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
     * IE: compile for 1.6 on JDK 1.8. When compiling for a different version, you jave that version's 1.6 rt.jar
     */
    @SuppressWarnings("AccessStaticViaInstance")
    public
    void build(final int targetJavaVersion) throws IOException {
        // exit early if we already built this project
        if (checkAndBuildDependencies(targetJavaVersion)) {
            return;
        }

        BuildLog.start();


        if (OS.javaVersion > targetJavaVersion) {
            BuildLog.title("Cross-Compile")
                    .println(this.name + "  [Java v1." + targetJavaVersion + "]");
        }
        else {
            BuildLog.title("Compiling").println(this.name);
        }
        if (this.versionString != null) {
            BuildLog.println(this.versionString);
        }


        shouldBuild = !verifyChecksums();

        if (!fullDependencyList.isEmpty()) {
            String[] array2 = new String[fullDependencyList.size() + 1];
            array2[0] = "Depends";
            int i = 1;
            for (Project<?> s : fullDependencyList) {
                array2[i++] = s.name;
                // if one of our dependencies has to build, so do we
                shouldBuild |= s.shouldBuild;
            }
            BuildLog.println().println((Object[]) array2);
        }

        if (shouldBuild) {
            // barf if we don't have source files!
            if (this.sourcePaths.getFiles().isEmpty()) {
                throw new IOException("No source files specified for project: " + this.name);
            }

            // make sure ALL dependencies are on the classpath.
            for (Project<?> project : fullDependencyList) {
                // dep can be a jar as well
                if (!project.outputFile.canRead()) {
                    throw new IOException("Dependency for project :" + this.name + " does not exist. '" +
                                          project.outputFile.getAbsolutePath() + "'");
                }

                // if we are compiling our build instructions (and projects), this won't exist. This is OK,
                // because we run from memory instead (in the classloader)
                this.classPaths.addFile(project.outputFile.getAbsolutePath());
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

                    if (OS.javaVersion > crossCompileClass.targetJavaVersion) {
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
                                                             .options(buildOptions)
                                                             .sourcePath(tempSource)
                                                             .sourcePath(sourceFiles);
                        tempProject.build(crossCompileClass.targetJavaVersion);

                        // now have to save out the source files (that are now converted to .class files)
                        for (File sourceFile : sourceFiles.getFiles()) {
                            String s = relativeLocations.get(sourceFile) + ".class";
                            File file = new File(tempProject.stagingDir, s);
                            FileUtil.copyFile(file, new File(crossCompatBuiltFile, s));
                        }

                        FileUtil.delete(tempProject.stagingDir);
                        BuildLog.enable();
                    }
                }

                // now have to add this dir to our project
                Paths crossFiles = new Paths();
                crossFiles.addFile(crossCompatBuiltFile.getAbsolutePath());
                classPath(crossFiles);

                Paths crossIncludeFiles = new Paths();
                for (String relativeName : relativeLocations.values()) {
                    crossIncludeFiles.add(crossCompatBuiltFile.getAbsolutePath(), relativeName + ".class");

                }
                extraFiles(crossIncludeFiles);


                // have to remove all the relative java files (since we don't want to normally build them)
                // the EASIEST way is to make a copy
                List<File> files = sourcePaths.getFiles();
                Paths copy = new Paths();
                for (File file : files) {
                    boolean canAdd = true;

                    for (File crossFile : relativeLocations.keySet()) {
                        if (crossFile.equals(file)) {
                            canAdd = false;
                            break;
                        }
                    }

                    if (canAdd) {
                        copy.add(file.getParent(), file.getName());
                    }
                }
                sourcePaths = copy;
            }
            // done with extra-file cross-compile
            BuildLog.println();



            if (this.bytesClassloader == null && this.jarable == null) {
                FileUtil.delete(this.stagingDir);
            }


            runCompile(targetJavaVersion);
            BuildLog.println("Compile success");

            if (this.jarable != null) {
                if (!keepOldFiles) {
                    // before we create the jar (and sources if necessary), we delete any of the old versions that might be in the target
                    // directory.
                    final File parentFile = this.outputFile.getParentFile();
                    final File[] files = parentFile.listFiles();
                    if (files != null) {
                        for (int i = 0; i < files.length; i++) {
                            File file = files[i];
                            if (file.isDirectory()) {
                                continue;
                            }

                            final String name = file.getName();

                            // if the file has our output name, delete it. We don't delete EVERYTHING, because there might be other files
                            // in this directory that we care about... also, only delete the files that we would be replacing.
                            if (name.startsWith(outputFileNameOnly)) {
                                file.delete();
                            }
                        }
                    }
                }


                this.jarable.buildJar();
            }

            // calculate the hash of all the files in the source path
            saveChecksums();

            if (this.mavenExporter != null) {
                this.mavenExporter.setProject(this);
                this.mavenExporter.export(targetJavaVersion);
            }

            if (crossCompatBuiltFile != null) {
                FileUtil.delete(crossCompatBuiltFile);
            }

            BuildLog.title("Staging").println(this.stagingDir);
        }
        else {
            BuildLog.println().println("Skipped (nothing changed)");
        }

        buildList.add(this.name);
        BuildLog.finish();
    }

    /**
     * Compiles into class files.
     */
    @SuppressWarnings("AccessStaticViaInstance")
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
            args.add(this.buildOptions.compiler.crossCompileLibrary.getCrossCompileLibraryLocation(targetJavaVersion));
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

    @Override
    public
    ProjectJava version(String versionString) {
        super.version(versionString);
        return this;
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
        if (this.jarable != null && !this.jarable.temporary) {
            if (this.outputFile.canRead()) {
                String jarChecksum = generateChecksum(this.outputFile);
                String checkContents = Builder.settings.get(this.outputFile.getAbsolutePath(), String.class);

                boolean outputFileGood = jarChecksum != null && jarChecksum.equals(checkContents);

                if (outputFileGood) {
                    if (!this.jarable.includeSourceAsSeparate) {
                        return true;
                    }
                    else {
                        // now check the src.zip file (if there was one).
                        jarChecksum = generateChecksum(this.outputFileSource);
                        checkContents = Builder.settings.get(this.outputFileSource.getAbsolutePath(), String.class);

                        return jarChecksum != null && jarChecksum.equals(checkContents);
                    }
                }
            }
            else {
                // output file was removed
                BuildLog.println("Output file was removed.");
                return false;
            }
        }

        return true;
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
            Builder.settings.save(this.outputFile.getAbsolutePath(), fileChecksum);

            if (this.jarable != null && this.jarable.includeSourceAsSeparate) {
                // now check the src.zip file (if there was one).
                fileChecksum = generateChecksum(this.outputFileSource);

                Builder.settings.save(this.outputFileSource.getAbsolutePath(), fileChecksum);
            }
        }
    }
}
