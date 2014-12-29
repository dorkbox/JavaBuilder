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
import dorkbox.util.Base64Fast;
import dorkbox.util.FileUtil;

public abstract class ProjectBasics {
    public static final String NO_PATH_TOKEN = "NO_PATH_TOKEN";

    public static final String Java_Pattern = "**" + File.separator + "*.java";
    public static final String Jar_Pattern = "**" + File.separator + "*.jar";
    public static final String STAGING = "staging";

    public static Map<String, ProjectBasics> deps = new LinkedHashMap<String, ProjectBasics>();
    private static Set<String> buildList = new HashSet<String>();

    private static boolean forceRebuild = false;
    private static boolean alreadyChecked = false;

    public static List<File> builderFiles = new ArrayList<File>();

    {
        if (!Build.isJar) {
            // check to see if our deploy code has changed. if yes, then we have to rebuild everything since
            // we don't know what might have changed.
            Paths paths = new Paths();
            File file = new File(ProjectBasics.class.getSimpleName() + ".java").getAbsoluteFile().getParentFile();
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

    public String name;

    protected Paths extraFiles = new Paths();

    public File outputFile;
    public File outputDir;

    protected Set<String> dependencies;

    protected boolean saveChecksums = true;
    private transient Paths checksumPaths = new Paths();


    public static ProjectBasics get(String projectName) {
        if (deps.containsKey(projectName)) {
            ProjectBasics project = deps.get(projectName);
            // put swt lib into jar!
            return project;
        } else {
            throw new IllegalArgumentException(projectName + " project must exist!");
        }
    }

    public static void buildAll(BuildOptions properties) throws Exception {
        for (ProjectBasics project : deps.values()) {
            ProjectBasics.build(project, properties);
        }
    }

    public static void build(String projectName, BuildOptions properties) throws Exception {
        ProjectBasics project = get(projectName);

        if (project != null) {
            project.build(properties);
        } else {
            System.err.println("Project is NULL. Aborting build.");
        }
    }

    public static void build(ProjectBasics project, BuildOptions properties) throws Exception {
        project.build(properties);
    }

    public static void remove(String outputDir) {
        deps.remove(outputDir);
    }

    protected ProjectBasics(String projectName) {
        this.name = projectName;

        String lowerCase_outputDir = projectName.toLowerCase();
        this.outputDir = new File(FileUtil.normalizeAsFile(STAGING + File.separator + lowerCase_outputDir));
        this.outputFile = new File(this.outputDir, this.name + getExtension());
    }

    public ProjectBasics depends(String dependsProjectName) {
        if (dependsProjectName == null) {
            throw new NullPointerException("Dependencies cannot be null!");
        }

        if (this.dependencies == null) {
            this.dependencies = new HashSet<String>(2);
        }
        this.dependencies.add(dependsProjectName);

        return this;
    }

    public ProjectBasics outputFile(String outputFileName) {
        this.outputFile = FileUtil.normalize(new File(outputFileName));
        return this;
    }

    /**
     * Checks to see if we already built this project. Also, will automatically build this projects
     * dependencies (if they haven't already been built).
     *
     * @return true if we can skip building this project
     */
    protected boolean checkAndBuildDependencies(BuildOptions options) throws Exception {
        // exit early if we already built this project
        if (buildList.contains(this.outputDir)) {
            Build.log().message("Skipped (built this run)");
            return true;
        }

        buildList.add(this.outputDir.getAbsolutePath());

        // ONLY build the dependencies as well
        if (this.dependencies != null) {
            for (String dep : this.dependencies) {
                ProjectBasics project = deps.get(dep);
                if (!buildList.contains(project.outputDir)) {
                    Build.log().message("Dependency - " + project.name);
                }
            }
            for (String dep : this.dependencies) {
                ProjectBasics project = deps.get(dep);
                if (!buildList.contains(project.outputDir)) {
                    project.build(options);
                }
            }
        }

        return false;
    }

    /** extra files to include when you jar the project */
    public ProjectBasics extraFiles(Paths filePaths) {
        this.extraFiles.add(filePaths);

        return this;
    }

    public abstract ProjectBasics build(BuildOptions properties) throws Exception;
    public abstract String getExtension();



    /**
     * Add a path to be checksum'd.
     */
    public final void checksum(Paths path) {
        this.checksumPaths.add(path);
    }

    /**
     * @return true if the checksums for path match the saved checksums and the jar file exists
     */
    boolean verifyChecksums(BuildOptions options) throws IOException {
        if (forceRebuild || options.compiler.forceRebuild) {
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
    @SuppressWarnings("unused")
    void saveChecksums(BuildOptions options) throws IOException {
        if (this.saveChecksums) {
            // hash/save the sources *and check-summed files* files
            String hashedContents = generateChecksums(this.checksumPaths);
            Build.settings.save(this.name, hashedContents);
        }
    }

    /**
     * Generates checksums for the given path
     */
    public static final String generateChecksum(File file) throws IOException {
        synchronized (ProjectBasics.class) {
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
        synchronized (ProjectBasics.class) {
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
        ProjectBasics other = (ProjectBasics) obj;
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