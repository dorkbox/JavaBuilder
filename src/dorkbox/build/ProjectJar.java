package dorkbox.build;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dorkbox.Build;
import dorkbox.util.FileUtil;

public class ProjectJar extends Project<ProjectJar> {

    private List<File> sources = new ArrayList<File>();
    private String distLocation;

    public static ProjectJar create(String projectName) {
        ProjectJar projectJar = new ProjectJar(projectName);
        deps.put(projectName, projectJar);
        return projectJar;
    }

    private ProjectJar(String projectName) {
        super(projectName);
    }


    public ProjectJar addSrc(String file) {
        this.sources.add(new File(FileUtil.normalizeAsFile(file)));
        return this;
    }

    public ProjectJar addSrc(File file) {
        this.sources.add(file);
        return this;
    }

    public void copyFiles(File targetLocation) throws IOException {
        // copy dist dir over
        boolean canCopySingles = false;
        if (this.distLocation != null) {
            Build.copyDirectory(this.distLocation, targetLocation.getAbsolutePath());
            if (this.outputFile == null || !this.outputFile.getAbsolutePath().startsWith(this.distLocation)) {
                canCopySingles = true;
            }
        } else {
            canCopySingles = true;
        }

        if (canCopySingles) {
            if (this.outputFile != null && this.outputFile.canRead()) {
                Build.copyFile(this.outputFile, new File(targetLocation, this.outputFile.getName()));
            }

            for (File f : this.sources) {
                Build.copyFile(f, new File(targetLocation, f.getName()));
            }
        }

        // now copy out extra files
        List<String> fullPaths = this.extraFiles.getPaths();
        List<String> relativePaths = this.extraFiles.getRelativePaths();


        for (int i = 0; i < fullPaths.size(); i++) {
            File source = new File(fullPaths.get(i));

            if (source.isFile()) {
                Build.copyFile(source, new File(targetLocation, relativePaths.get(i)));
            }
        }

        // now copy out dependencies
        for (String name : this.dependencies) {
            Project<?> project = deps.get(name);
            if (project instanceof ProjectJar) {
                ((ProjectJar) project).copyFiles(targetLocation);
            }
        }
    }

    @Override
    public String getExtension() {
        return ".jar";
    }

    @Override
    public void build() throws IOException {
    }

    public ProjectJar dist(String distLocation) {
        this.distLocation = FileUtil.normalizeAsFile(distLocation);
        return this;
    }
}
