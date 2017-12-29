package dorkbox.build;

import java.io.File;
import java.io.IOException;

import dorkbox.BuildVersion;
import dorkbox.build.util.BuildLog;
import dorkbox.util.storage.Storage;
import dorkbox.util.storage.StorageKey;
import dorkbox.util.storage.StorageSystem;

public class ProjectJar extends Project<ProjectJar> {

    public static
    ProjectJar create(String projectName) {
        ProjectJar projectJar = new ProjectJar(projectName);
        Project.create(projectJar);
        return projectJar;
    }

    // for serialization
    private
    ProjectJar() {
    }

    private
    ProjectJar(String projectName) {
        super(projectName);
    }

    @Override
    public
    boolean build(final int targetJavaVersion) throws IOException {
        return false;
    }

    @Override
    public
    String getExtension() {
        return Project.JAR_EXTENSION;
    }

    @Override
    public
    ProjectJar outputFile(final String outputFile, final String outputSourceFile) {
        if (!new File(outputFile).canRead()) {
            BuildLog.title("Error")
                    .println("Unable to read specified jar output file: '" + outputFile + "'");
        }

        if (outputSourceFile != null && !new File(outputSourceFile).canRead()) {
            BuildLog.title("Error")
                    .println("Unable to read specified jar output source file: '" + outputSourceFile + "'");
        }

        return outputFileNoWarn(outputFile, outputSourceFile);
    }

    @Override
    public
    ProjectJar dist(String distLocation) {
        super.dist(distLocation);
        return this;
    }

    @Override
    public
    ProjectJar version(BuildVersion version) {
        super.version(version);
        return this;
    }

    public
    ProjectJar outputFileNoWarn(final String outputFile, final String outputSourceFile) {
        hash.add(outputFile);
        if (outputSourceFile != null) {
            hash.add(outputSourceFile);
        }

        return super.outputFile(outputFile, outputSourceFile);
    }

    public
    ProjectJar outputFileNoWarn(final String outputFile) {
        return outputFileNoWarn(outputFile, null);
    }

    /**
     * Take all of the parameters of this project, and convert it to a text file.
     */
    @Override
    public
    void save(final String location) {
        Storage storage = StorageSystem.Disk()
                                       .file(location)
                                       .serializer(manager)
                                       .build();

        storage.put(new StorageKey(this.name), this);
        storage.save();
    }

    /**
     * Take all of the parameters of this project, and convert it to a text file.
     */
    public static
    ProjectJar get(final String projectName, final String location) {
        Storage storage = StorageSystem.Disk()
                                       .file(location)
                                       .serializer(manager)
                                       .logger(null)
                                       .build();

        return storage.get(new StorageKey(projectName));
    }
}
