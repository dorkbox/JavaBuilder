package dorkbox.build;

import dorkbox.Version;

import java.io.File;
import java.io.IOException;

public class ProjectJar extends Project<ProjectJar> {

    public static ProjectJar create(String projectName) {
        ProjectJar projectJar = new ProjectJar(projectName);
        deps.put(projectName, projectJar);
        return projectJar;
    }

    private ProjectJar(String projectName) {
        super(projectName);
    }

    @Override
    public ProjectJar addSrc(String file) {
        if (!new File(file).canRead()) {
            throw new RuntimeException("Unable to read specified jar source file: '" + file + "'");
        }

        super.addSrc(file);
        return this;
    }

    @Override
    public ProjectJar addSrc(File file) {
        if (!file.canRead()) {
            throw new RuntimeException("Unable to read specified jar source file: '" + file + "'");
        }

        super.addSrc(file);
        return this;
    }

    @Override
    public ProjectJar version(Version version) {
        super.version(version);
        return this;
    }

    @Override
    public String getExtension() {
        return Project.JAR_EXTENSION;
    }

    @Override
    public boolean
    build(final int targetJavaVersion) throws IOException {
        return false;
    }

    @Override
    public ProjectJar dist(String distLocation) {
        super.dist(distLocation);
        return this;
    }

    @Override
    public
    ProjectJar outputFile(final String outputFile) {
        if (!new File(outputFile).canRead()) {
            throw new RuntimeException("Unable to read specified jar output file: '" + outputFile + "'");
        }
        return super.outputFile(outputFile);
    }
}
