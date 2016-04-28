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
import com.twmacinta.util.MD5;
import dorkbox.BuildOptions;
import dorkbox.Builder;
import dorkbox.Version;
import dorkbox.build.util.BuildLog;
import dorkbox.build.util.OutputFile;
import dorkbox.build.util.ShutdownHook;
import dorkbox.license.License;
import dorkbox.util.Base64Fast;
import dorkbox.util.FileUtil;
import dorkbox.util.OS;
import org.bouncycastle.crypto.digests.MD5Digest;

import java.io.File;
import java.io.IOException;
import java.util.*;

@SuppressWarnings({"unchecked", "unused"})
public abstract
class Project<T extends Project<T>> {
    public static final String JAR_EXTENSION = ".jar";
    public static final String SRC_EXTENSION = "_src.zip";

    public static final String STAGING = "staging";

    public static final String Java_Pattern = "**" + File.separator + "*.java";
    public static final String Jar_Pattern = "**" + File.separator + "*.jar";

    public static Map<String, Project> deps = new LinkedHashMap<String, Project>();
    protected static Set<String> buildList = new HashSet<String>();

    private static boolean forceRebuild = false;
    private static boolean alreadyChecked = false;
    private static Comparator<Project> dependencyComparator = new ProjectComparator();

    // used to suppress certain messages when building deps
    protected boolean isBuildingDependencies = false;

    // most of the time, we save the build. Sometimes we don't want to save the build (ie: when running a test, for example)
    protected boolean shouldSaveBuild = true;

    public static List<File> builderFiles = new ArrayList<File>();
    public static Thread shutdownHook;

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
            String hashedContents = generateChecksums(paths);

            if (oldHash != null) {
                if (!oldHash.equals(hashedContents)) {
                    forceRebuild = true;
                }
            }
            else {
                forceRebuild = true;
            }

            if (forceRebuild) {
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
    }



    public final String name;

    protected Version version;

    File stagingDir;

    // if true, we do not delete the older versions during a build
    boolean keepOldVersion;

    public OutputFile outputFile;

    public List<File> sources = new ArrayList<File>();
    // could also be called native lib location
    private String distLocation;

    protected Paths extraFiles = new Paths();

    /** DIRECT dependencies for this project */
    protected List<Project<?>> dependencies = new ArrayList<Project<?>>();

    /** ALL related dependencies for this project (ie: recursively searched) */
    List<Project<?>> fullDependencyList = null;

    transient Paths checksumPaths = new Paths();
    protected List<License> licenses = new ArrayList<License>();
    protected BuildOptions buildOptions;

    private ArrayList<String> unresolvedDependencies = new ArrayList<String>();

    MavenExporter mavenExporter;
    public MavenInfo mavenInfo;

    /** true if we had to build this project */
    boolean shouldBuild = false;

    /** true if we skipped building this project */
    boolean skippedBuild = false;

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


    protected
    Project(String projectName) {
        this.name = projectName;
        this.buildOptions = new BuildOptions();

        String lowerCase_outputDir = projectName.toLowerCase();
        this.stagingDir = new File(FileUtil.normalizeAsFile(STAGING + File.separator + lowerCase_outputDir));
        // must call this method, because it's not overridden by jar type
        outputFile0(new File(this.stagingDir.getParentFile(), this.name + getExtension()).getAbsolutePath());
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
        mavenExport(new MavenExporter(new MavenInfo(groupId, name, this.version, scope)));
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
        mavenInfo = new MavenInfo(groupId, this.name, this.version, null); // null = Scope.COMPILE
        return (T)this;
    }

    /**
     * Specifies the specific maven info for this project, to configure dependencies
     */
    public
    T mavenInfo(final String groupId, final MavenInfo.Scope scope) {
        mavenInfo = new MavenInfo(groupId, this.name, this.version, scope);
        return (T)this;
    }

    /**
     * Specifies the specific maven info for this project, to configure dependencies
     */
    public
    T mavenInfo(final String groupId, final String artifactId, final MavenInfo.Scope scope) {
        mavenInfo = new MavenInfo(groupId, artifactId, this.version, scope);
        return (T)this;
    }

    /**
     * Specifies the specific maven info for this project, to configure dependencies
     */
    public
    T mavenInfo(final String groupId, final String artifactId, final String version, final MavenInfo.Scope scope) {
        mavenInfo = new MavenInfo(groupId, artifactId, new Version(version), scope);
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
     * Checks to see if we already built this project. Also, will automatically build this projects
     * dependencies (if they haven't already been built).
     */
    void resolveDependencies() throws IOException {
        resolveDeps();

        if (fullDependencyList == null) {
            // ONLY build the dependencies as well
            HashSet<Project<?>> deps = new HashSet<Project<?>>();
            getRecursiveDependencies(deps);

            Project<?>[] array = new Project<?>[deps.size()];
            deps.toArray(array);
            fullDependencyList = Arrays.asList(array);
            Collections.sort(fullDependencyList, dependencyComparator);
        }
    }


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
        this.extraFiles.add(new Paths(file.getParent(), file.getName()));
        return (T) this;
    }

    public
    T outputFile(File outputFile) {
        return outputFile(outputFile.getAbsolutePath());
    }


    /**
     * If the specified file is ONLY a filename, then it (and the source file, if necessary) will be placed into the staging directory.
     * If a path + name is specified, then they will be placed as is.
     * <p>
     * If no extension is provide, the default is '.jar'
     */
    public
    T outputFile(String outputFileName) {
        // this method is offset, for setting the output file via jar VS via constructor (constructor must always call outputFile0)
        return outputFile0(outputFileName);
    }

    private
    T outputFile0(String outputFileName) {
        // output file is used for hash checking AND for new builds

        if (!outputFileName.contains(File.separator)) {
            outputFileName = new File(this.stagingDir, outputFileName).getAbsolutePath();
        }

        this.outputFile = new OutputFile(version, outputFileName);

        return (T) this;
    }

    public
    Project<T> addSrc(String file) {
        this.sources.add(new File(FileUtil.normalizeAsFile(file)));
        return this;
    }

    public
    Project<T> addSrc(File file) {
        this.sources.add(file);
        return this;
    }

    public
    Project<T> dist(String distLocation) {
        this.distLocation = FileUtil.normalizeAsFile(distLocation);
        return this;
    }

    public
    Project<T> dist(File distLocation) {
        this.distLocation = FileUtil.normalize(distLocation).getAbsolutePath();
        return this;
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

    private boolean calledLicenseBefore = false;
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
    void copyFiles(String targetLocation) throws IOException {
        copyFiles(new File(FileUtil.normalizeAsFile(targetLocation)));
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

            for (File f : this.sources) {
                Builder.copyFile(f, new File(targetLocation, f.getName()));
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
        copyMainFiles(new File(FileUtil.normalizeAsFile(targetLocation)));
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


///////////////////////////////////////////////////////////////////////////////////////////////////////
//// CHECKSUM LOGIC
///////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Add a path to be checksum'd.
     */
    @SuppressWarnings("WeakerAccess")
    public final
    void checksum(Paths path) {
        this.checksumPaths.add(path);
    }

    /**
     * @return true if the checksums for path match the saved checksums and the jar file exists, false if the check failed and the
     * project needs to rebuild
     */
    boolean verifyChecksums() throws IOException {
        if (forceRebuild || this.buildOptions.compiler.forceRebuild) {
            return false;
        }

        // check to see if our SOURCES *and check-summed files* have changed.
        String hashedContents = generateChecksums(this.checksumPaths);
        String checkContents = Builder.settings.get(this.name, String.class);

        return hashedContents != null && hashedContents.equals(checkContents);
    }

    /**
     * Saves the checksums for a given path
     */
    void saveChecksums() throws IOException {
        // by default, we save the build. When building a 'test' build, we opt to NOT save the build hashes, so that a 'normal' build
        // will then compile.
        if (!buildOptions.compiler.saveBuild) {
            return;
        }

        // hash/save the sources *and check-summed files* files
        String hashedContents = generateChecksums(this.checksumPaths);
        Builder.settings.save(this.name, hashedContents);
    }

    /**
     * Generates checksums for the given path
     */
    public static
    String generateChecksum(File file) throws IOException {
        synchronized (Project.class) {
            // calculate the hash of file
            boolean found = false;
            if (file.isFile() && file.canRead()) {
                found = true;
            }

            if (!found) {
                return null;
            }

            byte[] hashBytes = MD5.getHash(file);

            return Base64Fast.encodeToString(hashBytes, false);
        }
    }

    /**
     * Generates checksums for the given path
     */
    public static
    String generateChecksums(Paths... paths) throws IOException {
        synchronized (Project.class) {
            // calculate the hash of all the files in the source path
            Set<String> names = new HashSet<String>(64);

            for (Paths path : paths) {
                names.addAll(path.getPaths());
            }

            // hash of hash of files. faster than using java to hash files
            MD5Digest md5_digest = new MD5Digest();

            boolean found = false;
            for (String name : names) {
                File file = new File(name);
                if (file.isFile() && file.canRead()) {
                    found = true;

                    byte[] hashBytes = MD5.getHash(file);
                    md5_digest.update(hashBytes, 0, hashBytes.length);
                }
            }

            if (!found) {
                return null;
            }

            byte[] hashBytes = new byte[md5_digest.getDigestSize()];
            md5_digest.doFinal(hashBytes, 0);

            return Base64Fast.encodeToString(hashBytes, false);
        }
    }

    public
    T version(Version version) {
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
            BuildLog.println("Deleting staging location: " + this.stagingDir);
            FileUtil.delete(this.stagingDir);

            final File[] files = this.stagingDir.listFiles();
            if (files == null || files.length == 0) {
                // we delete the entire staging dir, not just the one for ourselves, only if it's empty
                BuildLog.println("Deleting staging location" + this.stagingDir.getParentFile());
                FileUtil.delete(this.stagingDir.getParentFile());
            }
        }
    }

    /**
     * Will keep the old files. Meaning that if you have SuperCool-v1.4, and release SuperCool-v1.5; the v1.4 release will not be deleted.
     */
    public
    Project<?> keepOldVersion() {
        keepOldVersion = true;
        return this;
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

}
