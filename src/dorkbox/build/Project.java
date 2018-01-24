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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.minlog.Log;

import dorkbox.BuildOptions;
import dorkbox.BuildVersion;
import dorkbox.Builder;
import dorkbox.build.util.BuildLog;
import dorkbox.build.util.Hash;
import dorkbox.build.util.OutputFile;
import dorkbox.build.util.ShutdownHook;
import dorkbox.build.util.wildcard.Paths;
import dorkbox.license.License;
import dorkbox.util.FileUtil;
import dorkbox.util.OS;
import dorkbox.util.serialization.FileSerializer;
import dorkbox.util.serialization.SerializationManager;
import io.netty.buffer.ByteBuf;

@SuppressWarnings({"unchecked", "unused", "Convert2Diamond", "AccessStaticViaInstance"})
public abstract
class Project<T extends Project<T>> {
    public static final String JAR_EXTENSION = ".jar";
    public static final String SRC_EXTENSION = "_src.zip";

    public static final String STAGING = "staging";

    public static final String Java_Pattern = "**" + File.separator + "*.java";
    public static final String Jar_Pattern = "**" + File.separator + "*.jar";

    public static Map<String, Project> deps = new LinkedHashMap<String, Project>();
    protected static Set<String> buildList = new HashSet<String>();

    private static boolean forceRebuildAll = false;
    private static boolean alreadyChecked = false;
    private static Comparator<Project> dependencyComparator = new ProjectComparator();

    public static List<File> builderFiles = new ArrayList<File>();
    public static Thread shutdownHook;

    static final SerializationManager manager = new SerializationManager() {
        Kryo kryo = new Kryo();

        {
            // we don't want logging from Kryo...
            Log.set(Log.LEVEL_ERROR);

            register(File.class, new FileSerializer());
        }

        @Override
        public
        SerializationManager register(final Class<?> clazz) {
            kryo.register(clazz);
            return this;
        }

        @Override
        public
        SerializationManager register(final Class<?> clazz, final int id) {
            kryo.register(clazz, id);
            return this;
        }

        @Override
        public
        SerializationManager register(final Class<?> clazz, final Serializer<?> serializer) {
            kryo.register(clazz, serializer);
            return this;
        }

        @Override
        public
        SerializationManager register(final Class<?> type, final Serializer<?> serializer, final int id) {
            kryo.register(type, serializer, id);
            return this;
        }

        @Override
        public
        void write(final ByteBuf buffer, final Object message) {
            final Output output = new Output();
            writeFullClassAndObject(null, output, message);
            buffer.writeBytes(output.getBuffer());
        }

        @Override
        public
        Object read(final ByteBuf buffer, final int length) throws IOException {
            final Input input = new Input();
            buffer.readBytes(input.getBuffer());

            final Object o = readFullClassAndObject(null, input);
            buffer.skipBytes(input.position());

            return o;
        }

        @Override
        public
        void writeFullClassAndObject(final Logger logger, final Output output, final Object value) {
            kryo.writeClassAndObject(output, value);
        }

        @Override
        public
        Object readFullClassAndObject(final Logger logger, final Input input) throws IOException {
            return kryo.readClassAndObject(input);
        }

        @Override
        public
        void finishInit() {
        }

        @Override
        public
        boolean initialized() {
            return false;
        }
    };


    static {
        // check to see if our deploy code has changed. if yes, then we have to rebuild everything since
        // we don't know what might have changed.
        final Paths paths = new Paths();
        File file = new File(Project.class.getSimpleName() + ".java").getAbsoluteFile().getParentFile();
        paths.glob(file.getAbsolutePath(), Java_Pattern);

        for (File f : builderFiles) {
            paths.glob(f.getAbsolutePath(), Java_Pattern);
        }

        try {
            String oldHash = Builder.settings.get("BUILD", String.class);
            String hashedContents = Hash.generateChecksums(paths);

            if (oldHash != null) {
                if (!oldHash.equals(hashedContents)) {
                    forceRebuildAll = true;
                }
            }
            else {
                forceRebuildAll = true;
            }

            if (forceRebuildAll) {
                if (!alreadyChecked) {
                    alreadyChecked = true;
                    BuildLog.println("Build system changed. Rebuilding.");
                }

                // we only want to save the project checksums ON EXIT (so version modifications/save() can be applied)!
                shutdownHook = new Thread(new ShutdownHook(paths));

                Runtime.getRuntime().addShutdownHook(shutdownHook);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        Hash.forceRebuildAll = forceRebuildAll;
    }

    public static
    Project<?> create(Project<?> project) {
        deps.put(project.name, project);
        return project;
    }

    // removes all saved checksums as well as dependencies. Used to "reset everything", similar to if it was relaunched.
    public static
    void reset() {
        deps.clear();
        buildList.clear();

        BuildLog.start();
        BuildLog.title("RESET").println("All project info resetting...");
        BuildLog.finish();
    }



    public String name;

    protected BuildVersion version;

    protected File stagingDir;

    // if true, we do not delete the older versions during a build
    boolean keepOldVersion;

    public OutputFile outputFile;

    // could also be called native lib location
    private String distLocation;

    public Paths extraFiles = new Paths();

    /** DIRECT dependencies for this project */
    protected List<Project<?>> dependencies = new ArrayList<Project<?>>();

    /** Dependencies that are just source code files, not a jar. These are converted into a jar when the project is build */
    protected Paths sourceDependencies = new Paths();

    protected Paths sourcePaths = new Paths();

    /** ALL related dependencies for this project (ie: recursively searched) */
    protected transient List<Project<?>> fullDependencyList = null;

    // used to make sure licenses are called in the correct spot
    private transient boolean calledLicenseBefore = false;

    protected List<License> licenses = new ArrayList<License>();
    protected BuildOptions buildOptions;

    private ArrayList<String> unresolvedDependencies = new ArrayList<String>();

    // used to suppress certain messages when building deps
    protected transient boolean isBuildingDependencies = false;

    // used to suppress logging what dependencies are used
    private boolean suppressDependencyLog = false;

    // Sometimes we don't want to export the build to maven (ie: when running a test, for example)
    protected boolean exportToMaven = false;

    MavenExporter mavenExporter;
    public MavenInfo mavenInfo;

    /** true if we had to build this project */
    transient boolean shouldBuild = false;

    /** true if we skipped building this project */
    transient boolean skippedBuild = false;

    // true if we should rebuild this project
    boolean forceRebuild = false;

    protected transient Hash hash;

    /**
     * Temporary projects are always built, but not always exported to maven (this is controlled by the parent, non-temp project
     * recursively)
     */
    public boolean temporary = false;
    boolean overrideTemporary = false;

    public String description;

    public static
    Project<?> get(String projectName) {
        if (deps.containsKey(projectName)) {
            Project<?> project = deps.get(projectName);
            // put swt lib into jar!
            return project;
        }
        else {
            throw new IllegalArgumentException(projectName + " project must exist!");
        }
    }

    public static
    void buildAll() throws Exception {
        // organize the list of items to build, so that our build order is at least SOMEWHAT in order,
        // were the dependencies build first. This is just an optimization step
        List<Project> sorted = new ArrayList<Project>(deps.values());

        Collections.sort(sorted, dependencyComparator);
        for (Project project : sorted) {
            if (!(project instanceof ProjectJar)) {
                project.build();
            }
        }
    }


    /**
     * resolves all of the dependencies for this project, since the build order can be specified in ANY order
     */
    protected
    void resolveDeps() {
        Iterator<String> iterator = this.unresolvedDependencies.iterator();
        while (iterator.hasNext()) {
            String unresolved = iterator.next();
            Project<?> project = deps.get(unresolved);
            if (project != null) {
                this.dependencies.add(project);
                iterator.remove();
            }
        }
    }

    public static
    void build(String projectName) throws Exception {
        Project<?> project = get(projectName);

        if (project != null) {
            project.build();
        }
        else {
            System.err.println("Project is NULL. Aborting build.");
        }
    }

    public static
    void remove(String outputDir) {
        deps.remove(outputDir);
    }

    // for serialization
    protected
    Project() {
    }

    protected
    Project(String projectName) {
        this.name = projectName;
        this.buildOptions = new BuildOptions();

        String lowerCase_outputDir = projectName.toLowerCase();
        this.stagingDir = FileUtil.normalize(STAGING + File.separator + lowerCase_outputDir);
        // must call this method, because it's not overridden by jar type
        outputFile0(new File(this.stagingDir.getParentFile(), this.name + getExtension()).getAbsolutePath(), null);

        hash = new Hash(projectName, buildOptions);
    }

    /**
     * Builds using the current, detected JDK.
     *
     * @return true if this project was built, false otherwise
     */
    public final
    boolean build() throws IOException {
        return build(OS.javaVersion);
    }


    /**
     * Builds using the current, detected JDK.
     *
     * @return true if this project was built, false otherwise
     */
    public abstract
    boolean build(final int targetJavaVersion) throws IOException;


    /**
     * Exports this project to the maven central repository
     */
    public
    MavenExporter mavenExport(final String groupId, final MavenInfo.Scope scope) {
        mavenExport(new MavenExporter(new MavenInfo(groupId, name, this.version.toString(), scope)));
        return mavenExporter;
    }

    /**
     * Exports this project to the maven central repository
     */
    public
    T mavenExport(MavenExporter exporter) {
        mavenExporter = exporter;
        return (T)this;
    }

    /**
     * Specifies the specific maven info for this project, to configure dependencies
     */
    public
    T mavenInfo(final String groupId) {
        mavenInfo = new MavenInfo(groupId, this.name, this.version.toString(), null); // null = Scope.COMPILE
        return (T)this;
    }

    /**
     * Specifies the specific maven info for this project, to configure dependencies
     */
    public
    T mavenInfo(final MavenInfo mavenInfo) {
        this.mavenInfo = mavenInfo;
        return (T)this;
    }

    /**
     * Specifies the specific maven info for this project, to configure dependencies
     */
    public
    T mavenInfo(final String groupId, final MavenInfo.Scope scope) {
        mavenInfo = new MavenInfo(groupId, this.name, this.version.toString(), scope);
        return (T)this;
    }

    /**
     * Specifies the specific maven info for this project, to configure dependencies
     */
    public
    T mavenInfo(final String groupId, final String artifactId, final MavenInfo.Scope scope) {
        mavenInfo = new MavenInfo(groupId, artifactId, this.version.toString(), scope);
        return (T)this;
    }

    /**
     * Specifies the specific maven info for this project, to configure dependencies
     */
    public
    T mavenInfo(final String groupId, final String artifactId, final String version, final MavenInfo.Scope scope) {
        mavenInfo = new MavenInfo(groupId, artifactId, version, scope);
        return (T)this;
    }

    public
    List<License> getLicenses() {
        return this.licenses;
    }

    public abstract
    String getExtension();


    public
    void getRecursiveLicenses(Set<License> licenses) {
        licenses.addAll(this.licenses);

        Set<Project<?>> deps = new HashSet<Project<?>>();
        getRecursiveDependencies(deps);

        for (Project<?> project : deps) {
            project.getRecursiveLicenses(licenses);
        }
    }


    public
    void getRecursiveDependencies(Set<Project<?>> dependencies) {
        for (Project<?> project : this.dependencies) {
            dependencies.add(project);
            project.getRecursiveDependencies(dependencies);
        }
    }

    /**
     * Checks to see if we already built this project. Also, will automatically build this project's
     * dependencies (if they haven't already been built).
     */
    protected
    void resolveDependencies() {
        resolveDeps();

        if (fullDependencyList == null) {
            // ONLY build the dependencies as well
            HashSet<Project<?>> deps = new HashSet<Project<?>>();
            getRecursiveDependencies(deps);

            Project<?>[] array = new Project<?>[deps.size()];
            deps.toArray(array);
            fullDependencyList = Arrays.asList(array);
            fullDependencyList.sort(dependencyComparator);
        }
    }

    /**
     * suppress logging what dependencies are used
     */
    public
    T suppressDependencyLog() {
        suppressDependencyLog = true;
        return (T) this;
    }

    /**
     * Outputs all of the dependency information for a build
     */
    protected
    void logDependencies() {
        if (!suppressDependencyLog && !fullDependencyList.isEmpty()) {
            String[] array2 = new String[fullDependencyList.size() + 1];
            array2[0] = "Depends";
            int i = 1;
            for (Project<?> project : fullDependencyList) {
                array2[i] = project.name;

                if (project.version != null) {
                    array2[i] += " " + project.version;
                }

                if (project instanceof ProjectJar) {
                    array2[i] += " (jar)";
                }

                i++;
            }
            BuildLog.println().println((Object[]) array2).println();
        }
    }

    /**
     * Add paths to the list of sources that are available when compiling the code
     */
    public
    T sourcePath(Paths sourcePaths) {
        if (sourcePaths == null) {
            throw new NullPointerException("Source paths cannot be null!");
        }
        this.sourcePaths.add(sourcePaths);

        // ALWAYS add the source paths to be checksumed!
        hash.add(sourcePaths);

        return (T) this;
    }

    /**
     * Add paths to the list of sources that are available when compiling the code
     */
    public
    T sourcePath(String srcDir) {
        if (srcDir.endsWith("src")) {
            String parent = new File(srcDir).getAbsoluteFile()
                                            .getParent();
            hash.add(new Paths(parent));
        }

        return sourcePath(new Paths(srcDir, "./"));
    }

    /**
     * Add paths to the list of sources that are available when compiling the code
     */
    public
    T sourcePath(String dir, String... patterns) {
        return sourcePath(new Paths(dir, patterns));
    }

    /**
     * Add a class to the list of sources that are available when compiling the code
     */
    public
    T sourcePath(final Class<?> sourceClass) {
        return sourcePath(Builder.getJavaFile(sourceClass));
    }

    /**
     * requires output file to exist for build to succeed
     */
    public
    T depends(String projectOrJar) {
        if (projectOrJar == null) {
            throw new NullPointerException("Dependencies cannot be null!");
        }

        // sometimes it's a jar file, not a project
        File file = new File(projectOrJar);
        if (file.canRead()) {
            ProjectJar.create(projectOrJar).outputFile(file);
        }

        Project<?> project = deps.get(projectOrJar);
        if (project != null) {
            this.dependencies.add(project);
        }
        else {
            this.unresolvedDependencies.add(projectOrJar);
        }

        return (T) this;
    }


    /**
     * requires output file to exist for build to succeed
     */
    public
    T depends(Project<?> project) {
        if (project == null) {
            throw new NullPointerException("Dependencies cannot be null!");
        }

        this.licenses.addAll(project.licenses);
        this.dependencies.add(project);

        return (T) this;
    }

    /**
     * Adds java files, as Paths, to be built. This removes the need for building a temp jar first.
     */
    public
    T depends(final Paths dependencies) {
        hash.add(dependencies);
        sourceDependencies.add(dependencies);

        return (T) this;
    }

    /**
     * Adds java files, as Paths, to be built. This removes the need for building a temp jar first.
     */
    public
    T depends(final Class<?> dependencyClass) {
        return depends(Builder.getJavaFile(dependencyClass));
    }

    /**
     * @return a list of all projects that are recursive dependencies of this project. ONLY VALID AFTER A BUILD
     */
    public
    List<Project<?>> getFullDependencyList() {
        return fullDependencyList;
    }

    /**
     * extra files to include when you jar the project
     * <p/>
     * The TARGET location in the JAR, is the RELATIVE location when adding the paths. <br>
     * For Example: <br>
     * extraFiles(new Paths("foo", "bar/x.bmp"))  <br>
     * jar  <br>
     * - a  <br>
     * - b  <br>
     * - bar/x.bmp  <br>
     * <br>
     * extraFiles(new Paths("foo/bar", "x.bmp"))  <br>
     * jar  <br>
     * - a  <br>
     * - b  <br>
     * - x.bmp
     */
    public
    T extraFiles(Paths filePaths) {
        this.extraFiles.add(filePaths);
        return (T) this;
    }

    public
    T extraFiles(File file) {
        Paths paths = new Paths(file.getParent(), file.getName());
        Iterator<File> fileIterator = paths.fileIterator();

        while (fileIterator.hasNext()) {
            File next = fileIterator.next();

            if (!next.canRead()) {
                BuildLog.title("Error").println("Unable to read specified extra file: '" + file.getAbsolutePath() + "'");
            }
        }

        this.extraFiles.add(paths);
        return (T) this;
    }

    /**
     * @return all of the extra files for this project
     */
    public
    Paths extraFiles() {
        return this.extraFiles;
    }

    public
    T outputFile(final File outputFile) {
        return outputFile(outputFile.getAbsolutePath(), null);
    }

    public
    T outputFile(final File outputFile, final File outputSourceFile) {
        return outputFile(outputFile.getAbsolutePath(), outputSourceFile.getAbsolutePath());
    }

    public
    T outputFile(final String outputFileName) {
        return outputFile(outputFileName, null);
    }

    /**
     * If the specified file is ONLY a filename, then it (and the source file, if necessary) will be placed into the staging directory.
     * If a path + name is specified, then they will be placed as is.
     * <p>
     * If no extension is provide, the default is '.jar'
     */
    public
    T outputFile(String outputFileName, String outputSourceFileName) {
        // this method is offset, for setting the output file via jar VS via constructor (constructor must always call outputFile0)
        // if the constructor calls outputFile() instead, it will not work because of how methods are overloaded.
        return outputFile0(outputFileName, outputSourceFileName);
    }

    private
    T outputFile0(String outputFileName, String outputSourceFileName) {
        // output file is used for hash checking AND for new builds
        if (!outputFileName.contains(File.separator)) {
            outputFileName = new File(this.stagingDir, outputFileName).getAbsolutePath();
        }

        if (outputSourceFileName != null && !outputSourceFileName.contains(File.separator)) {
            outputSourceFileName = new File(this.stagingDir, outputSourceFileName).getAbsolutePath();
        }

        this.outputFile = new OutputFile(version, outputFileName, outputSourceFileName);

        return (T) this;
    }


    public
    T dist(String distLocation) {
        this.distLocation = FileUtil.normalize(distLocation).getAbsolutePath();
        return (T) this;
    }

    public
    T dist(File distLocation) {
        this.distLocation = FileUtil.normalize(distLocation).getAbsolutePath();
        return (T) this;
    }

    public
    T options(BuildOptions buildOptions) {
        this.buildOptions = buildOptions;
        return (T) this;
    }

    public
    BuildOptions options() {
        if (this.buildOptions == null) {
            this.buildOptions = new BuildOptions();
        }
        return this.buildOptions;
    }

    /**
     * This call needs to be (at least) before dependencies are added, otherwise the order of licenses might be in the incorrect order.
     * Preferably, this should be the very first call.
     */
    public
    T license(License license) {
        if (!calledLicenseBefore) {
            calledLicenseBefore = true;

            if (!this.licenses.isEmpty()) {
                BuildLog.println("This is the first license added, yet there are already licenses' present. This is probably not the order of " +
                                 "licenses that you want. We suggest calling .license() before adding dependencies.");
            }
        }

        this.licenses.add(license);
        return (T) this;
    }

    /**
     * This call needs to be (at least) before dependencies are added, otherwise the order of licenses might be in the incorrect order.
     * Preferably, this should be the very first call.
     */
    public
    T license(List<License> licenses) {
        if (!calledLicenseBefore) {
            calledLicenseBefore = true;

            if (!this.licenses.isEmpty()) {
                BuildLog.println("This is the first license added, yet there are already licenses' present. This is probably not the order of " +
                                 "licenses that you want. We suggest calling .license() before adding dependencies.");
            }
        }

        this.licenses.addAll(licenses);
        return (T) this;
    }

    public
    File getStagingDir() {
        return stagingDir;
    }


    public
    void copyFiles(String targetLocation) throws IOException {
        copyFiles(FileUtil.normalize(targetLocation));
    }

    public
    void copyFiles(File targetLocation) throws IOException {
        File file = null;
        if (this.outputFile != null) {
            file = this.outputFile.get();
        }

        // copy dist dir over
        boolean canCopySingles = false;
        if (this.distLocation != null) {
            Builder.copyDirectory(this.distLocation, targetLocation.getAbsolutePath());

            if (file == null || !file.getAbsolutePath().startsWith(this.distLocation)) {
                canCopySingles = true;
            }
        }
        else {
            canCopySingles = true;
        }

        if (canCopySingles) {
            if (file != null && file.canRead()) {
                Builder.copyFile(file, new File(targetLocation, file.getName()));
            }

            File source = null;
            if (this.outputFile != null) {
                source = this.outputFile.getSource();
            }

            // do we have a "source" file as well?
            if (source != null && source.canRead()) {
                Builder.copyFile(source, new File(targetLocation, source.getName()));
            }
        }

        // now copy out extra files
        List<String> fullPaths = this.extraFiles.getPaths();
        List<String> relativePaths = this.extraFiles.getRelativePaths();


        for (int i = 0; i < fullPaths.size(); i++) {
            File source = new File(fullPaths.get(i));

            if (source.isFile()) {
                Builder.copyFile(source, new File(targetLocation, relativePaths.get(i)));
            }
        }

        // now copy out dependencies
        for (Project<?> project : this.dependencies) {
            if (project instanceof ProjectJar) {
                project.copyFiles(targetLocation);
            }
        }
    }

    public
    void copyMainFiles(String targetLocation) throws IOException {
        copyMainFiles(FileUtil.normalize(targetLocation));
    }


    @SuppressWarnings("WeakerAccess")
    public
    void copyMainFiles(File targetLocation) throws IOException {
        if (this.outputFile != null) {
            final File file = this.outputFile.get();
            final File source = this.outputFile.getSource();


            if (file != null && file.canRead()) {
                Builder.copyFile(file, new File(targetLocation, file.getName()));
            }

            // do we have a "source" file as well?
            if (source != null && source.canRead()) {
                Builder.copyFile(source, new File(targetLocation, source.getName()));
            }
        }
    }

    public
    T version(BuildVersion version) {
        this.version = version;
        return (T) this;
    }

    /**
     * This cleans up and deletes the staging directory
     */
    public
    void cleanup() {
        if (fullDependencyList != null) {
            for (Project<?> project : fullDependencyList) {
                 project.cleanup();
            }
        }

        if (!skippedBuild && !keepOldVersion && !(getClass().getSimpleName().equals(ProjectJar.class.getSimpleName()))) {
            // before we create the jar (and sources if necessary), we delete any of the old versions that might be in the target
            // directory.
            if (this.version != null) {
                if (this.version.hasChanged()) {
                    final File originalJar = this.outputFile.getOriginal();
                    final File originalSource = this.outputFile.getSourceOriginal();

                    Builder.delete(originalJar);
                    Builder.delete(originalSource);
                }
            }
            else {
                final File originalJar = this.outputFile.getOriginal();
                final File originalSource = this.outputFile.getSourceOriginal();

                if (!this.outputFile.get().equals(originalJar) &&
                    !this.outputFile.getSource().equals(originalSource)) {
                    
                    Builder.delete(originalJar);
                    Builder.delete(originalSource);
                }
            }
        }

        if (stagingDir.exists()) {
            BuildLog.start();
            BuildLog.title("Cleanup").println("Deleting staging location: " + this.stagingDir);
            FileUtil.delete(this.stagingDir);

            final File[] files = this.stagingDir.listFiles();
            if (files == null || files.length == 0) {
                // we delete the entire staging dir, not just the one for ourselves, only if it's empty
                BuildLog.println("Deleting staging location" + this.stagingDir.getParentFile());
                FileUtil.delete(this.stagingDir.getParentFile());
            }
            BuildLog.finish();
        }
    }

    /**
     * Will keep the old files. Meaning that if you have SuperCool-v1.4, and release SuperCool-v1.5; the v1.4 release will not be deleted.
     */
    public
    T keepOldVersion() {
        keepOldVersion = true;
        return (T) this;
    }

    /**
     * Description used for the build process
     */
    public
    T description(String description) {
        this.description = description;
        return (T) this;
    }

    /**
     * Description used for the build process
     */
    public
    T description(License license) {
        this.description = license.notes.get(0);
        return (T) this;
    }

    /**
     * Sets this project to be temporary, meaning that the decision to build this does NOT depend on the output files from this project
     * existing, meaning that this project will ALWAYS build if the parent, non-temp project needs it to (ie: source code changes)
     */
    public
    T temporary() {
        temporary = true;
        return (T) this;
    }

    @Override
    public
    String toString() {
        return this.name;
    }

    @Override
    public
    int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.name == null ? 0 : this.name.hashCode());
        return result;
    }

    @Override
    public
    boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        Project<?> other = (Project<?>) obj;
        if (this.name == null) {
            if (other.name != null) {
                return false;
            }
        }
        else if (!this.name.equals(other.name)) {
            return false;
        }
        return true;
    }

    public
    void uploadToMaven() throws IOException {
        if (buildOptions.compiler.saveBuild) {
            // only if we save the build. Test builds don't save, and we shouldn't upload them to maven
        }
    }

    /**
     * Saves the project details to the specified location
     * @param location
     */
    public abstract
    void save(final String location);

    /**
     * Forces a particular build to always build, even if it has been built before
     */
    public
    void forceRebuild() {
        forceRebuild = true;
    }
}
