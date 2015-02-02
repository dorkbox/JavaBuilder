package dorkbox.build;

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
        super.addSrc(file);
        return this;
    }

    @Override
    public ProjectJar addSrc(File file) {
        super.addSrc(file);
        return this;
    }

    @Override
    public ProjectJar version(String versionString) {
        super.version(versionString);
        return this;
    }

    @Override
    public String getExtension() {
        return Project.JAR_EXTENSION;
    }

    @Override
    public void build() throws IOException {
    }

    @Override
    public ProjectJar dist(String distLocation) {
        super.dist(distLocation);
        return this;
    }
}
