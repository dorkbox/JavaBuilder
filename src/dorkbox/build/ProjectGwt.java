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
import dorkbox.Builder;
import dorkbox.build.util.BuildLog;
import dorkbox.build.util.jar.JarUtil;
import dorkbox.util.FileUtil;
import dorkbox.util.gwt.GwtSymbolMapParser;
import dorkbox.util.process.JavaProcessBuilder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

public class ProjectGwt extends Project<ProjectGwt> {

    private String[] extraOptions;
    private String projectLocation;
    protected Paths sourcePaths = new Paths();

    public static ProjectGwt create(String projectName, String projectLocation) {
        ProjectGwt project = new ProjectGwt(projectName, projectLocation);
        deps.put(projectName, project);

        return project;
    }


    protected ProjectGwt(String projectName, String projectLocation) {
       super(projectName);

       this.projectLocation = projectLocation;
    }

    public ProjectGwt sourcePath(Paths sourcePaths) {
        if (sourcePaths == null) {
            throw new NullPointerException("Source paths cannot be null!");
        }
        this.sourcePaths.add(sourcePaths);

        return this;
    }

    public ProjectGwt sourcePath(String srcDir) {
        if (srcDir.endsWith("src")) {
            String parent = new File(srcDir).getAbsoluteFile().getParent();
            checksum(new Paths(parent));
        }

        return sourcePath(new Paths(srcDir, "./"));
    }

    public ProjectGwt sourcePath(String dir, String... patterns) {
        return sourcePath(new Paths(dir, patterns));
    }

    /**
     * GWT only cares about the output dir (it doesn't make jars for compiling)
     * @return true if the checksums for path match the saved checksums and the jar file exists
     */
    @Override
    boolean verifyChecksums() throws IOException {
        boolean sourceHashesSame = super.verifyChecksums();
        if (!sourceHashesSame) {
            return false;
        }

        // if the sources are the same, check the output dir
        if (this.stagingDir.exists()) {
            String dirChecksum = generateChecksum(this.stagingDir);
            String checkContents = Builder.settings.get(this.stagingDir.getAbsolutePath(), String.class);

            return dirChecksum != null && dirChecksum.equals(checkContents);
        }

        return true;
    }

    /**
     * GWT only cares about the output dir (it doesn't make jars for compiling)
     * Saves the checksums for a given path
     */
    @Override
    void saveChecksums() throws IOException {
        super.saveChecksums();

        // hash/save the output files (if there are any)
        if (this.stagingDir.exists()) {
            String fileChecksum = generateChecksum(this.stagingDir);
            Builder.settings.save(this.stagingDir.getAbsolutePath(), fileChecksum);
        }
    }


    /**
     * This uses the same gwt symbol parser as the web-server project.
     */
    public void build(final int targetJavaVersion) throws IOException {
        // exit early if we already built this project
        if (checkAndBuildDependencies(targetJavaVersion)) {
            return;
        }

        boolean shouldBuild = false;
        try {
            // GWT checksum requirements are different than regular java.
            shouldBuild = !verifyChecksums();

            if (shouldBuild) {
                // make sure our dependencies are on the classpath.
                if (this.dependencies != null) {
                    for (Project<?> project : this.dependencies) {
                        if (project != null) {
                            this.sourcePaths.addFile(project.outputFile.getAbsolutePath());
                        }
                    }
                }


                FileUtil.delete(this.stagingDir);
                FileUtil.mkdir(this.stagingDir);

                String clientString = "Client";

                String stagingWar = Builder.path(STAGING, "war");
                String stagingWarWebINF = Builder.path(stagingWar, "WEB-INF");
                String stagingWarWebINFDeploy = Builder.path(stagingWarWebINF, "deploy");


                String stagingJunk = Builder.path(STAGING, "junk");
                String stagingUnitCache = Builder.path(STAGING, "gwt-unitCache");
                String stagingSymbols = Builder.path(STAGING, "symbolMaps");
                String clientTempLocation = Builder.path(STAGING, clientString + "_javascript");


                String srcWarPath = Builder.path("..", this.projectLocation, "war");

                // make the output directory
                FileUtil.delete(stagingWar);
                FileUtil.delete(stagingSymbols);
                FileUtil.delete(clientTempLocation);


                FileUtil.mkdir(stagingWar);
                FileUtil.mkdir(stagingUnitCache);

                if (this.buildOptions.compiler.release) {
                    FileUtil.delete(stagingUnitCache);
                }


                System.err.println("Compiling GWT modules. This can take a while....");
                System.err.println("  Working location: " + FileUtil.normalize(new File("test")).getParent());

                JavaProcessBuilder builder = new JavaProcessBuilder(System.in, System.out, System.err);
                builder.setMaximumHeapSizeInMegabytes(512);

                // we want to DEBUG this! (figure out wtf is going on)
//                builder.addJvmOption("-Xdebug");
//                builder.addJvmOption("-Xrunjdwp:transport=dt_socket,server=y,address=1044");

                builder.setMainClass("com.google.gwt.dev.Compiler");

                // The Java classpath should include:
                //  - the Java source code of your application
                //  - gwt-dev.jar, gwt-user.jar,
                //  - any compiler-visible resources such as ui.xml and .png files,
                //  - and the COMPILED versions of any generators and linkers added or used by the build.

                // the GWT compiler needs the source/parent directory of the XML files
                builder.addJvmClasspaths(this.sourcePaths.getPaths());

                // prevent phone-home of google GWT compiler
                builder.addArgument("-XdisableUpdateCheck");

                // EXPERIMENTAL: Disables some java.lang.Class methods (e.g. getName())
                builder.addArgument("-XdisableClassMetadata");

                //Logs output in a graphical tree view
                builder.addArgument("-treeLogger");

                // fail if there are any warnings
                builder.addArgument("-strict");

                // The directory into which deployable output files will be written (defaults to 'war')
                builder.addArgument("-war " + stagingWar);

                // we want the compiler to save the symbols map (so we can correctly do the mapping for message-bus de-obfuscation)
                builder.addArgument("-deploy " + stagingWarWebINFDeploy);

                // The directory into which extra files, not intended for deployment, will be written
                builder.addArgument("-extra " + stagingJunk);

                // Additional arguments like -style PRETTY/OBFuscated/DETAILED or -logLevel INFO/WARN/ERROR/TRACE/DEBUG/SPAM/ALL
                // logLevel is log level during compile.
                builder.addArgument("-logLevel INFO");
//                builder.addArgument("-logLevel TRACE");

                for (String option : this.extraOptions) {
                    builder.addArgument(option);
                }

                // DETAILED, PRETTY, OBF[USCATED]
                if (this.buildOptions.compiler.debugEnabled) {
//                    builder.addArgument("-style PRETTY");
                    builder.addArgument("-style DETAILED");
                } else {
                    builder.addArgument("-style OBF");
                }

                if (this.buildOptions.compiler.release) {
                    // generate the gwt cache files EVERY time
                    builder.addArgument("-Dgwt.usearchives=false");
                }


                // must be last
                builder.addArgument("hive." + clientString);

                builder.start();


                // move the symbol maps to the correct spot
                FileUtil.mkdir(stagingSymbols);
                Paths symbolMapPaths = new Paths(Builder.path(stagingWarWebINFDeploy, clientString, "symbolMaps"), "*.symbolMap");
                for (File file : symbolMapPaths.getFiles()) {
                    // clean up the symbolmaps first!
                    parseAndCopyGwtSymbols(file, new File(Builder.path(stagingSymbols, file.getName())));
                }


                // move the client generated javascript to the correct spot
                FileUtil.moveDirectory(Builder.path(stagingWar, clientString), clientTempLocation);


                // remove directories that are not needed/wanted in the deployment
                FileUtil.delete(stagingWar);
                FileUtil.delete(stagingJunk);

                if (this.buildOptions.compiler.release) {
                    FileUtil.delete(stagingUnitCache);
                }


                // create the NEW war directory
                FileUtil.mkdir(stagingWar);
                // todo: pass in the hive-webclient project name somehow?
                Paths warFiles = new Paths(srcWarPath, "*.html", "*.ico");
                for (File file : warFiles.getFiles()) {
                    FileUtil.copyFile(file.getAbsolutePath(), Builder.path(stagingWar, file.getName()));
                }

                // copy over images
                FileUtil.copyDirectory(Builder.path(srcWarPath, "images"), Builder.path(stagingWar, "images"), ".svn");


                // make the web-INF directory
                FileUtil.mkdir(stagingWarWebINF);

                // move the symbolMaps into the correct spot.
                FileUtil.moveDirectory(stagingSymbols, Builder.path(stagingWarWebINF, "symbolMaps"));

                // add any extra files to the output war dir.
                File warDir = new File(stagingWar);

                List<String> paths = this.extraFiles.getPaths();
                List<String> paths2 = this.extraFiles.getRelativePaths();
                for (int i=0;i<paths.size();i++) {
                    String path = paths.get(i);
                    String dest = paths2.get(i);
                    FileUtil.copyFile(path, new File(warDir, dest).getPath());
                }

                // move the client generated javascript to the correct spot
                FileUtil.moveDirectory(clientTempLocation, Builder.path(stagingWar, clientString));


                // war it up
                warFiles = new Paths(stagingWar, "**");

                List<String> fullPaths = warFiles.filesOnly().getPaths();
                List<String> relativePaths = warFiles.filesOnly().getRelativePaths();

                if (this.outputFile.exists()) {
                    this.outputFile.delete();
                }
                FileUtil.mkdir(this.outputFile.getParent());


                // make a jar (really a war file)
                JarUtil.war(this.outputFile.getAbsolutePath(), fullPaths, relativePaths);

                // cleanup
                FileUtil.delete(stagingWar);

                // calculate the hash of all the files in the source path
                saveChecksums();
            } else {
                BuildLog.println().println("Skipped (nothing changed)");
            }
        } finally {
            if (shouldBuild) {
                FileUtil.delete(this.stagingDir);
            }
        }

        System.err.println("   Build success: " + this.stagingDir);
    }

    public ProjectGwt options(String... options) {
        if (this.extraOptions != null) {
            List<String> origList = Arrays.asList(this.extraOptions);
            List<String> newList = Arrays.asList(options);

            origList.addAll(newList);
            this.extraOptions = origList.toArray(new String[0]);
        } else {
            this.extraOptions = options;
        }

        return this;
    }

    /**
     * Parses the relevant data from the symbol map and saves it in the specified file.
     */
    public static void parseAndCopyGwtSymbols(File sourceFile, File destSymbolFile) throws IOException {
        GwtSymbolMapParser parser = new GwtSymbolMapParser();

        //FileReader always assumes default encoding is OK!
        FileInputStream fileInputStream = new FileInputStream(sourceFile);
        parser.parse(fileInputStream);

        destSymbolFile.getParentFile().mkdirs();
        Writer output = new BufferedWriter(new FileWriter(destSymbolFile));

        try {
            // FileWriter always assumes default encoding is OK
            output.write("# BUILD-SCRIPT MODIFIED: only relevant symbols are present.\n");

            StringBuilder stringBuilder = new StringBuilder();
            for (Entry<String, String> entry : parser.getSymbolMap().entrySet()) {
                stringBuilder.delete(0, stringBuilder.capacity());
                // create a "shrunk" version for deployment. This is marginally better than the original version
                stringBuilder
                    .append(entry.getKey()) // jsName
                    .append(",")
                    .append(entry.getValue()) // java className
                    .append("\n");


                String line = stringBuilder.toString();

                // FileWriter always assumes default encoding is OK
                output.write(line);
            }
        } finally {
            output.close();
        }
    }

    @Override
    public String getExtension() {
        return ".war";
    }
}
