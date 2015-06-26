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
import dorkbox.Builder;
import dorkbox.BuildOptions;
import dorkbox.build.util.BuildLog;
import dorkbox.license.License;
import dorkbox.util.Base64Fast;
import dorkbox.util.FileUtil;
import org.bouncycastle.crypto.digests.MD5Digest;

import java.io.File;
import java.io.IOException;
import java.util.*;

public abstract
class Project<T extends Project<T>> {
    public static final String JAR_EXTENSION = ".jar";
    public static final String SRC_EXTENSION = "_src.zip";

    public static final String STAGING = "staging";

    public static final String Java_Pattern = "**" + File.separator + "*.java";
    public static final String Jar_Pattern = "**" + File.separator + "*.jar";

    @SuppressWarnings("rawtypes")
    public static Map<String, Project> deps = new LinkedHashMap<String, Project>();
    protected static Set<String> buildList = new HashSet<String>();

    private static boolean forceRebuild = false;
    private static boolean alreadyChecked = false;

    // used to suppress certain messages when building deps
    protected boolean isBuildingDependencies = false;

    public static List<File> builderFiles = new ArrayList<File>();

    static {
        if (!Builder.isJar) {
            // check to see if our deploy code has changed. if yes, then we have to rebuild everything since
            // we don't know what might have changed.
            Paths paths = new Paths();
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
                        Builder.log().println("Build system changed. Rebuilding.");
                    }
                    Builder.settings.save("BUILD", hashedContents);
                }
            } catch (IOException e) {
            }
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

    protected String versionString;
    // has the version been set?
    protected boolean setVersion = false;

    public File stagingDir;
    public File outputFile;
    public File outputFileSource = null;

    public List<File> sources = new ArrayList<File>();
    // could also be called native lib location
    private String distLocation;

    protected Paths extraFiles = new Paths();

    protected List<Project<?>> dependencies = new ArrayList<Project<?>>();

    private transient Paths checksumPaths = new Paths();
    protected List<License> licenses = new ArrayList<License>();
    protected BuildOptions buildOptions;

    private ArrayList<String> unresolvedDependencies = new ArrayList<String>();


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

    @SuppressWarnings("rawtypes")
    public static
    void buildAll() throws Exception {
        // organize the list of items to build, so that our build order is at least SOMEWHAT in order,
        // were the dependencies build first. This is just an optimization step
        List<Project> copy = new ArrayList<Project>(deps.values());

        Collections.sort(copy, new Comparator<Project>() {
            @Override
            public
            int compare(Project o1, Project o2) {
                if (o1 == null || o1.dependencies.isEmpty()) {
                    return -1;
                }
                else if (o2 == null || o2.dependencies.isEmpty()) {
                    return 1;
                }

                return o1.name.compareTo(o2.name);
            }
        });

        List<Project> sorted = new ArrayList<Project>(copy.size());
        Set<String> sortedCheck = new HashSet<String>(0);

        while (true) {
            Iterator<Project> iterator = copy.iterator();
            while (iterator.hasNext()) {
                Project<?> next = iterator.next();
                // first, we have to make sure that ALL of the dependencies are RESOLVED
                next.resolveDeps();

                if (next.dependencies.isEmpty()) {
                    sorted.add(next);
                    sortedCheck.add(next.name);
                    iterator.remove();
                }
                else {
                    List<Project<?>> list = next.dependencies;
                    int size = list.size();

                    for (Project<?> d : list) {
                        if (sortedCheck.contains(d.name)) {
                            size--;
                        }
                    }

                    if (size == 0) {
                        sorted.add(next);
                        sortedCheck.add(next.name);
                        iterator.remove();
                    }
                }
            }

            if (copy.isEmpty()) {
                break;
            }
        }

        for (Project project : sorted) {
            if (!(project instanceof ProjectJar)) {
                BuildLog.start();
                project.build();
                BuildLog.finish();
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
        outputFile(new File(this.stagingDir.getParentFile(), this.name + getExtension()));
    }

    public abstract
    void build() throws IOException;

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
     *
     * @return true if we can skip building this project
     */
    @SuppressWarnings("all")
    protected
    boolean checkAndBuildDependencies() throws IOException {
        resolveDeps();

        // exit early if we already built this project
        if (buildList.contains(this.name)) {
            if (!this.isBuildingDependencies) {
                Builder.log().title("Building").println(this.name + " already built this run");
            }
            return true;
        }

        // ONLY build the dependencies as well
        HashSet<Project<?>> deps = new HashSet<Project<?>>();
        getRecursiveDependencies(deps);

        if (!deps.isEmpty()) {
            String[] array = new String[deps.size() + 1];
            array[0] = "Depends";
            int i = 1;
            for (Project<?> s : deps) {
                array[i++] = s.name;
            }
            Builder.log().title(this.name).println((Object[]) array);
        }

        for (Project<?> project : deps) {
            // dep can be a jar as well (don't have to build a jar)
            if (!(project instanceof ProjectJar)) {
                if (!buildList.contains(project.name)) {
                    // check the hashes to see if the project changed before we build it.
                    // a change in the hashes would ALSO mean a dependency of it changed as well.

                    boolean nothingChangd = project.verifyChecksums();
                    if (!nothingChangd) {
                        boolean prev = project.isBuildingDependencies;
                        project.isBuildingDependencies = true;
                        BuildLog.start();

                        project.build();

                        BuildLog.finish();
                        project.isBuildingDependencies = prev;
                    }
                }
            }
        }

        return false;
    }


    @SuppressWarnings("unchecked")
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


    @SuppressWarnings("unchecked")
    public
    T depends(Project<?> project) {
        if (project == null) {
            throw new NullPointerException("Dependencies cannot be null!");
        }

        this.dependencies.add(project);
        this.licenses.addAll(project.licenses);

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
    @SuppressWarnings("unchecked")
    public
    T extraFiles(Paths filePaths) {
        this.extraFiles.add(filePaths);
        return (T) this;
    }

    public
    T outputFile(File outputFile) {
        return outputFile(outputFile.getAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    public
    T outputFile(String outputFileName) {
        this.setVersion = true;
        String withVersionName;
        // version string is appended to the fileName
        if (this.versionString != null) {
            int index = outputFileName.lastIndexOf('.');
            if (index < 0) {
                withVersionName = outputFileName + "_" + this.versionString;
            }
            else {
                String first = outputFileName.substring(0, index);
                String extension = outputFileName.substring(index);

                withVersionName = first + "_" + this.versionString + extension;
            }
        }
        else {
            withVersionName = outputFileName;
        }

        this.outputFile = FileUtil.normalize(new File(withVersionName));


        String name = this.outputFile.getAbsolutePath();
        int lastIndexOf = name.lastIndexOf('.');
        if (lastIndexOf > 0) {
            name = name.substring(0, lastIndexOf);
        }

        name += SRC_EXTENSION;
        this.outputFileSource = new File(name);

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

    @SuppressWarnings("unchecked")
    public
    T options(BuildOptions buildOptions) {
        this.buildOptions = buildOptions;
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public
    T license(License license) {
        this.licenses.add(license);
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public
    T license(List<License> licenses) {
        this.licenses.addAll(licenses);
        return (T) this;
    }


    public
    void copyFiles(File targetLocation) throws IOException {
        // copy dist dir over
        boolean canCopySingles = false;
        if (this.distLocation != null) {
            Builder.copyDirectory(this.distLocation, targetLocation.getAbsolutePath());

            if (this.outputFile == null || !this.outputFile.getAbsolutePath().startsWith(this.distLocation)) {
                canCopySingles = true;
            }
        }
        else {
            canCopySingles = true;
        }

        if (canCopySingles) {
            if (this.outputFile != null && this.outputFile.canRead()) {
                Builder.copyFile(this.outputFile, new File(targetLocation, this.outputFile.getName()));
            }

            // do we have a "source" file as well?
            if (this.outputFileSource != null && this.outputFileSource.canRead()) {
                Builder.copyFile(this.outputFileSource, new File(targetLocation, this.outputFileSource.getName()));
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
                ((ProjectJar) project).copyFiles(targetLocation);
            }
        }
    }


///////////////////////////////////////////////////////////////////////////////////////////////////////
//// CHECKSUM LOGIC
///////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Add a path to be checksum'd.
     */
    public final
    void checksum(Paths path) {
        this.checksumPaths.add(path);
    }

    /**
     * @return true if the checksums for path match the saved checksums and the jar file exists
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
        // hash/save the sources *and check-summed files* files
        String hashedContents = generateChecksums(this.checksumPaths);
        Builder.settings.save(this.name, hashedContents);
    }

    /**
     * Generates checksums for the given path
     */
    public static final
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

            String fileChecksums = Base64Fast.encodeToString(hashBytes, false);
            return fileChecksums;
        }
    }

    /**
     * Generates checksums for the given path
     */
    public static final
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

            String fileChecksums = Base64Fast.encodeToString(hashBytes, false);
            return fileChecksums;
        }
    }

    public
    Project<?> version(String versionString) {
        this.versionString = versionString;
        return this;
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
}
