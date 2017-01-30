package dorkbox.build;

import java.io.File;
import java.io.IOException;

import dorkbox.Version;
import dorkbox.build.util.BuildLog;
import dorkbox.build.util.FileNotFoundRuntimeException;

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
        BuildLog.title("Error")
                .println("Cannot specify a source file in this manner for a jar. Please set the source along with the output file");
        throw new FileNotFoundRuntimeException("Invalid file: " + file);
    }

    @Override
    public ProjectJar addSrc(File file) {
        BuildLog.title("Error")
                .println("Cannot specify a source file in this manner for a jar. Please set the source along with the output file");
        throw new FileNotFoundRuntimeException("Invalid file: " + file);
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

    public
    ProjectJar outputFileNoWarn(final String outputFile) {
        return outputFileNoWarn(outputFile, null);
    }

    public
    ProjectJar outputFileNoWarn(final String outputFile, final String outputSourceFile) {
        this.checksumPaths.addFile(outputFile);
        if (outputSourceFile != null) {
            this.checksumPaths.addFile(outputSourceFile);
        }

        return super.outputFile(outputFile, outputSourceFile);
    }
}
