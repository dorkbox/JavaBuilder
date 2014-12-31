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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bouncycastle.crypto.digests.MD5Digest;

import com.esotericsoftware.wildcard.Paths;
import com.twmacinta.util.MD5;

import dorkbox.Build;
import dorkbox.BuildOptions;
import dorkbox.build.util.BuildLog;
import dorkbox.license.License;
import dorkbox.util.Base64Fast;
import dorkbox.util.FileUtil;

public abstract class Project<T extends Project<T>> {
    public static final String STAGING = "staging";

    public static final String Java_Pattern = "**" + File.separator + "*.java";
    public static final String Jar_Pattern = "**" + File.separator + "*.jar";

    @SuppressWarnings("rawtypes")
    public static Map<String, Project> deps = new LinkedHashMap<String, Project>();
    private static Set<String> buildList = new HashSet<String>();

    private static boolean forceRebuild = false;
    private static boolean alreadyChecked = false;

    // used to suppress certain messages when building deps
    protected boolean isBuildingDependencies = false;

    public static List<File> builderFiles = new ArrayList<File>();
    static {
        if (!Build.isJar) {
            // check to see if our deploy code has changed. if yes, then we have to rebuild everything since
            // we don't know what might have changed.
            Paths paths = new Paths();
            File file = new File(Project.class.getSimpleName() + ".java").getAbsoluteFile().getParentFile();
            paths.glob(file.getAbsolutePath(), Java_Pattern);

            for (File f : builderFiles) {
                paths.glob(f.getAbsolutePath(), Java_Pattern);
            }

            try {
                String oldHash = Build.settings.get("BUILD", String.class);
                String hashedContents = generateChecksums(paths);

                if (oldHash != null) {
                    if (!oldHash.equals(hashedContents)) {
                        forceRebuild = true;
                    }
                } else {
                    forceRebuild = true;
                }

                if (forceRebuild) {
                    if (!alreadyChecked) {
                        alreadyChecked = true;
                        Build.log().message("Build system changed. Rebuilding.");
                    }
                    Build.settings.save("BUILD", hashedContents);
                }
            } catch (IOException e) {
            }
        }
    }


    // removes all saved checksums as well as dependencies. Used to "reset everything", similar to if it was relaunched.
    public static void reset() {
        deps.clear();
        buildList.clear();
    }



    public final String name;

    public File stagingDir;
    public File outputFile;
    protected Paths extraFiles = new Paths();

    protected List<String> dependencies = new ArrayList<String>();

    private transient Paths checksumPaths = new Paths();
    protected List<License> licenses = new ArrayList<License>();
    protected BuildOptions buildOptions;


    @SuppressWarnings("rawtypes")
    public static Project get(String projectName) {
        if (deps.containsKey(projectName)) {
            Project project = deps.get(projectName);
            // put swt lib into jar!
            return project;
        } else {
            throw new IllegalArgumentException(projectName + " project must exist!");
        }
    }

    @SuppressWarnings("rawtypes")
    public static void buildAll() throws Exception {
        // organize the list of items to build, so that the entries WITH NO DEPENDENCIES always build first!
        List<Project> copy = new ArrayList<Project>(deps.values());

        Collections.sort(copy, new Comparator<Project>() {
            @Override
            public int compare(Project o1, Project o2) {
                if (o1 == null || o1.dependencies == null || o1.dependencies.isEmpty()) {
                    return -1;
                } else if (o2 == null || o2.dependencies == null || o2.dependencies.isEmpty()) {
                    return 1;
                }

                return 0;
            }});


        for (Project project : copy) {
            Project.build(project);
        }
    }

    @SuppressWarnings("rawtypes")
    public static void build(String projectName) throws Exception {
        Project project = get(projectName);

        if (project != null) {
            project.build();
        } else {
            System.err.println("Project is NULL. Aborting build.");
        }
    }

    @SuppressWarnings("rawtypes")
    public static void build(Project project) throws Exception {
        project.build();
    }

    public static void remove(String outputDir) {
        deps.remove(outputDir);
    }












    protected Project(String projectName) {
        this.name = projectName;
        this.buildOptions = new BuildOptions();

        String lowerCase_outputDir = projectName.toLowerCase();
        this.stagingDir = new File(FileUtil.normalizeAsFile(STAGING + File.separator + lowerCase_outputDir));
        this.outputFile = new File(this.stagingDir.getParentFile(), this.name + getExtension());
    }

    public abstract void build() throws IOException;
    public abstract String getExtension();


    /**
     * Can be a jar name or a project name
     */
    private void getRecursiveDependencies(Set<String> dependencies) {
        for (String dep : this.dependencies) {
            String dependencyName = dep;
            Project<?> project = deps.get(dependencyName);
            if (project != null) {
                dependencies.add(project.name);
                project.getRecursiveDependencies(dependencies);
            } else {
                dependencies.add(dep);
            }
        }
    }

    /**
     * Checks to see if we already built this project. Also, will automatically build this projects
     * dependencies (if they haven't already been built).
     *
     * @return true if we can skip building this project
     */
    @SuppressWarnings("all")
    protected boolean checkAndBuildDependencies() throws IOException {
        // exit early if we already built this project
        if (buildList.contains(this.name)) {
            if (!this.isBuildingDependencies) {
                Build.log().title("Building").message(this.name + " already built this run");
            }
            return true;
        }

        // ONLY build the dependencies as well
        HashSet<String> deps = new HashSet<String>();
        getRecursiveDependencies(deps);

        if (!deps.isEmpty()) {
            String[] array = new String[deps.size() + 1];
            array[0] = "Depends";
            int i = 1;
            for (String s : deps) {
                array[i++] = s;
            }
            Build.log().title(this.name).message(array);
        }

        for (String dep : deps) {
            Project<?> project = Project.deps.get(dep);
            // dep can be a jar as well (don't have to build a jar)
            if (project != null) {
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

        buildList.add(this.name);
        return false;
    }


    @SuppressWarnings("unchecked")
    public T depends(String dependsProjectName) {
        if (dependsProjectName == null) {
            throw new NullPointerException("Dependencies cannot be null!");
        }

        this.dependencies.add(dependsProjectName);

        return (T) this;
    }


    @SuppressWarnings("unchecked")
    public T depends(T project) {
        if (project == null) {
            throw new NullPointerException("Dependencies cannot be null!");
        }

        this.dependencies.add(project.name);
        return (T) this;
    }



    /** extra files to include when you jar the project */
    public T extraFiles(String dir, String... patterns) {
        if (new File(dir).isFile() && (patterns == null || patterns.length == 0)) {
            Paths paths = new Paths();
            paths.addFile(dir);
            return extraFiles(paths);
        }
        return extraFiles(new Paths(dir, patterns));
    }

    /** extra files to include when you jar the project */
    @SuppressWarnings("unchecked")
    public T extraFiles(Paths filePaths) {
        this.extraFiles.add(filePaths);
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public T outputFile(String outputFileName) {
        this.outputFile = FileUtil.normalize(new File(outputFileName));
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public T outputFile(File outputFile) {
        this.outputFile = FileUtil.normalize(outputFile);
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public T options(BuildOptions buildOptions) {
        this.buildOptions = buildOptions;
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public T license(License license) {
        this.licenses.add(license);
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public T license(List<License> licenses) {
        this.licenses.addAll(licenses);
        return (T) this;
    }

///////////////////////////////////////////////////////////////////////////////////////////////////////
//// CHECKSUM LOGIC
///////////////////////////////////////////////////////////////////////////////////////////////////////
    /**
     * Add a path to be checksum'd.
     */
    public final void checksum(Paths path) {
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
        String checkContents = Build.settings.get(this.name, String.class);

        return hashedContents != null && hashedContents.equals(checkContents);
    }

    /**
     * Saves the checksums for a given path
     */
    void saveChecksums() throws IOException {
        // hash/save the sources *and check-summed files* files
        String hashedContents = generateChecksums(this.checksumPaths);
        Build.settings.save(this.name, hashedContents);
    }

    /**
     * Generates checksums for the given path
     */
    public static final String generateChecksum(File file) throws IOException {
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
    public static final String generateChecksums(Paths... paths) throws IOException {
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

    @Override
    public String toString() {
        return this.name;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.name == null ? 0 : this.name.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        @SuppressWarnings("rawtypes")
        Project<?> other = (Project) obj;
        if (this.name == null) {
            if (other.name != null) {
                return false;
            }
        } else if (!this.name.equals(other.name)) {
            return false;
        }
        return true;
    }
}