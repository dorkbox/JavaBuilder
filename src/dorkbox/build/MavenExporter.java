/*
 * Copyright 2015 dorkbox, llc
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

import ch.qos.logback.classic.Level;
import com.ning.http.client.*;
import com.ning.http.client.providers.jdk.JDKAsyncHttpProvider;
import com.ning.http.multipart.FilePart;
import com.ning.http.multipart.StringPart;
import dorkbox.Builder;
import dorkbox.build.util.BuildLog;
import dorkbox.license.License;
import dorkbox.util.Base64Fast;
import dorkbox.util.OS;
import dorkbox.util.Sys;
import dorkbox.util.crypto.CryptoPGP;
import org.bouncycastle.openpgp.PGPException;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

/**
 * Creates pom files and exports the project to sonatype.org OSSRH
 */
public
class MavenExporter {
    private static final String NL = OS.LINE_SEPARATOR;

    private static final String repositoryId = "repositoryId";
    private static final String profileId = "profileId";

    private String groupId;
    private String version;

    private String githubUrl;
    private String gitHubParent;
    private String gitHubProject;

    private String propertyFile;
    private boolean keepOnServer = false;
    private ProjectJava projectJava;

    static {
        // set the user-agent for NING. This is so it is clear who/what uploaded to sonatype
        System.setProperty(AsyncHttpClientConfig.class.getName() + "." + "userAgent", "JavaBuilder/1.0");
    }


    public
    MavenExporter(final String version) {
        this.version = version.replace("v", "").replace("V", "");
    }

    public
    MavenExporter id(final String groupId) {
        this.groupId = groupId;

        return this;
    }

    /**
     * Keeps the repo on the sonatype server. Usually this is for debugging.
     */
    public
    MavenExporter keep() {
        keepOnServer = true;
        return this;
    }

    public
    MavenExporter repoInfo(final String githubUrl, final String gitHubParent, final String gitHubProject) {
        this.githubUrl = githubUrl;
        this.gitHubParent = gitHubParent;
        this.gitHubProject = gitHubProject;

        return this;
    }

    void setProject(ProjectJava projectJava) {
        this.projectJava = projectJava;
    }

    public
    ProjectJava credentials(final String propertyFile) {
        this.propertyFile = propertyFile;

        return projectJava;
    }

    /**
     * This is called by the build.
     *
     * There is a limit of roughly 1024MB on any single file uploaded to OSSRH. Your uploads will fail with a broken pipe exception
     * when you hit this limit. Contact us directly if you need to upload larger components.
     *
     * @throws IOException
     */
    @SuppressWarnings("AccessStaticViaInstance")
    public
    void export(final int targetJavaVersion) throws IOException {
        String fileName = projectJava.name + "-" + version;
        fileName = new File(projectJava.stagingDir, fileName).getAbsolutePath();

        BuildLog.start();
        BuildLog.title("MAVEN").println("Exporting to sonatype");
        final File pomFile = new File(fileName + ".pom");
        final File jarFile = new File(fileName + ".jar");
        final File sourcesFile = new File(fileName + "-sources.jar");
        final File docsFile = new File(fileName + "-javadoc.jar");

        Properties properties;
        FileInputStream inStream = null;
        try {
            // fetch info from the properties file
            properties = new Properties();
            inStream = new FileInputStream(new File(propertyFile));
            properties.load(inStream);
        } finally {
            Sys.close(inStream);
        }

        String signPrivateKey = properties.getProperty("signaturePrivateKey");
        String signUserId = properties.getProperty("signatureUserId");
        String signPasswordString = properties.getProperty("signaturePassword");

        char[] signPassword = null;
        if (signPasswordString != null) {
            signPassword = signPasswordString.toCharArray();
        }

        String repoId = properties.getProperty("repoId");
        String username = properties.getProperty("username");
        String password = properties.getProperty("password");

        if (!"OSSRH".equalsIgnoreCase(repoId)) {
            throw new RuntimeException("Only sonatype OSSRH supported at this time. Please create a github issue to support other types.");
        }


        BuildLog.println("Creating the POM file");
        String pomFileText = createString(projectJava, targetJavaVersion, properties);

        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(pomFile));
            writer.write(pomFileText);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        Builder.copyFile(projectJava.outputFile, jarFile);
        Builder.copyFile(projectJava.outputFileSource, sourcesFile);


        BuildLog.title("Creating fake docs").println("  " + docsFile);
        try {
            writer = new BufferedWriter(new FileWriter(docsFile));
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        BuildLog.println();
        BuildLog.title("Signing files").println();

        try {
            BuildLog.println(pomFile.getName());
            CryptoPGP.signGpgCompatible(new FileInputStream(signPrivateKey), signUserId, signPassword, pomFile);

            BuildLog.println(jarFile.getName());
            CryptoPGP.signGpgCompatible(new FileInputStream(signPrivateKey), signUserId, signPassword, jarFile);

            BuildLog.println(sourcesFile.getName());
            CryptoPGP.signGpgCompatible(new FileInputStream(signPrivateKey), signUserId, signPassword, sourcesFile);

            BuildLog.println(docsFile.getName());
            CryptoPGP.signGpgCompatible(new FileInputStream(signPrivateKey), signUserId, signPassword, docsFile);
        } catch (PGPException e) {
            throw new RuntimeException("Unable to PGP sign files.", e);
        }


        final File pomAscFile = new File(pomFile.getAbsolutePath() + ".asc");
        final File jarAscFile = new File(jarFile.getAbsolutePath() + ".asc");
        final File sourcesAscFile = new File(sourcesFile.getAbsolutePath() + ".asc");
        final File docsAscFile = new File(docsFile.getAbsolutePath() + ".asc");


        // disable the logger for NING
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(JDKAsyncHttpProvider.class);
        logger.setLevel(Level.OFF);

        // now upload the bundle
        final String authInfo = Base64Fast.encodeToString((username + ":" + password).getBytes(OS.UTF_8), false);

        RequestBuilder builder;
        Request request;


        BuildLog.title("Uploading files").println();

        final String nameAndVersion = projectJava.name + " v" + version;
        final String description = "Automated build of " + nameAndVersion + " by JavaBuilder";
        String uploadURL = "https://oss.sonatype.org/service/local/staging/upload";


        String repo;
        String profile;


        // first we upload POM + main jar
        BuildLog.println(jarFile.getName());
        builder = new RequestBuilder("POST");
        request = builder.setUrl(uploadURL)
                         .addHeader("Authorization", "Basic " + authInfo)

                         .addBodyPart(new StringPart("hasPom", "true"))
                         .addBodyPart(new StringPart("c", ""))
                         .addBodyPart(new StringPart("e", "jar"))  // extension
                         .addBodyPart(new StringPart("desc", description))

                         .addBodyPart(new FilePart("file", pomFile))
                         .addBodyPart(new FilePart("file", jarFile))
                         .build();

        final String s = sendHttpRequest(request);

        final int repositoryIndex = s.lastIndexOf(repositoryId);
        repo = s.substring(repositoryIndex + repositoryId.length() + 3, s.length() - 2);

        final int profileIndex = s.lastIndexOf(profileId);
        profile = s.substring(profileIndex + profileId.length() + 3, repositoryIndex - 3);


        final Uploader uploader = new Uploader(projectJava.name, uploadURL, repo, authInfo, groupId, version, description);
        String groupID_asPath = groupId.replaceAll("\\.", "/");

        // now POM signature
        BuildLog.println(pomAscFile.getName());
        uploader.upload(pomAscFile, "pom.asc");
        deleteSignatureTurds(authInfo, repo, groupID_asPath, projectJava.name, version, pomAscFile);



        // now jar signature
        BuildLog.println(jarAscFile.getName());
        uploader.upload(jarAscFile, "jar.asc");
        deleteSignatureTurds(authInfo, repo, groupID_asPath, projectJava.name, version, jarAscFile);



        // now sources
        BuildLog.println(sourcesFile.getName());
        uploader.upload(sourcesFile, "jar", "sources");

        // now sources signature
        BuildLog.println(sourcesAscFile.getName());
        uploader.upload(sourcesAscFile, "jar.asc", "sources");
        deleteSignatureTurds(authInfo, repo, groupID_asPath, projectJava.name, version, sourcesAscFile);



        // now javadoc
        BuildLog.println(docsFile.getName());
        uploader.upload(docsFile, "jar", "javadoc");


        // now javadoc signature
        BuildLog.println(docsAscFile.getName());
        uploader.upload(docsAscFile, "jar.asc", "javadoc");
        deleteSignatureTurds(authInfo, repo, groupID_asPath, projectJava.name, version, docsAscFile);



        // Now tell the repo we are finished (and the build will verify everything)



        BuildLog.title("Closing Repo").println();
        String hasErrors = closeRepo(authInfo, profile, repo, nameAndVersion);


        if (!hasErrors.isEmpty()) {
            BuildLog.title("Errors!").println("'" + hasErrors + "'");
            // DROP THE REPO (in case of errors)
            hasErrors = "Keeping repo on server...";
            if (!keepOnServer) {
                hasErrors = dropRepo(authInfo, profile, repo, nameAndVersion);
            }

            if (!hasErrors.isEmpty()) {
                BuildLog.println("'" + hasErrors + "'");
            }
        }
        else {
            // now to promote it to a release!

            hasErrors = " "; // has to be non-empty.

            BuildLog.print("Releasing ");
            int retryCount = 10;


            // we have to WAIT until it's ready!
            // if we go too quickly, we'll get this error:  {"errors":[{"id":"*","msg":"Unhandled: Repository: comdorkbox-1024 has invalid state: open"}]}
            while (!hasErrors.isEmpty() && retryCount-- >= 0) {
                // sleep for xx seconds (initially) and also for retries
                try {
                    Thread.sleep(2000L);
                    BuildLog.print(".");
                    Thread.sleep(2000L);
                    BuildLog.print(".");
                    Thread.sleep(2000L);
                    BuildLog.print(".");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                hasErrors = promoteRepo(authInfo, profile, repo, nameAndVersion);

                if (hasErrors.isEmpty()) {
                    BuildLog.println(" Released");
                    BuildLog.title("Access URL").println("https://oss.sonatype.org/content/repositories/releases/" + groupID_asPath +
                                                         "/" + projectJava.name + "/" + version + "/");

                    // NOW we drop the repo (since it was a success!
                    hasErrors = "Keeping repo on server";
                    if (!keepOnServer) {
                        hasErrors = dropRepo(authInfo, profile, repo, nameAndVersion);
                    }

                    if (!hasErrors.isEmpty()) {
                        BuildLog.println("'" + hasErrors + "'");
                    }

                    // clear the error, because it was successfully promoted to release
                    hasErrors = "";
                    break;
                }
            }

            if (!hasErrors.isEmpty()) {
                BuildLog.println();
                BuildLog.println("Error for repo '" + repo + "' during promotion to release! '" + hasErrors + "'");
                throw new RuntimeException("Error promoting to release! Please log-in manually and correct the problem.");
            }
        }

        BuildLog.finish();
    }

    /**
     * Closes the repo and (the server) will verify everything is correct.
     * @throws IOException
     */
    private static
    String closeRepo(final String authInfo, final String profile, final String repo, final String nameAndVersion) throws IOException {

        String repoInfo = "{'data':{'stagedRepositoryId':'" + repo + "','description':'Closing " + nameAndVersion + "'}}";
        RequestBuilder builder = new RequestBuilder("POST");
        Request request = builder.setUrl("https://oss.sonatype.org/service/local/staging/profiles/" + profile + "/finish")
                                 .addHeader("Content-Type", "application/json")
                                 .addHeader("Authorization", "Basic " + authInfo)

                                 .setBody(repoInfo.getBytes(OS.UTF_8))

                                 .build();

        return sendHttpRequest(request);
    }

    /**
     * Promotes (ie: release) the repo. Make sure to drop when done
     * @throws IOException
     */
    private static
    String promoteRepo(final String authInfo, final String profile, final String repo, final String nameAndVersion) throws IOException {

        String repoInfo = "{'data':{'stagedRepositoryId':'" + repo + "','description':'Promoting " + nameAndVersion + "'}}";
        RequestBuilder builder = new RequestBuilder("POST");
        Request request = builder.setUrl("https://oss.sonatype.org/service/local/staging/profiles/" + profile + "/promote")
                         .addHeader("Content-Type", "application/json")
                         .addHeader("Authorization", "Basic " + authInfo)

                         .setBody(repoInfo.getBytes(OS.UTF_8))

                         .build();
        return sendHttpRequest(request);
    }

    /**
     * Drops the repo
     * @throws IOException
     */
    private static
    String dropRepo(final String authInfo, final String profile, final String repo, final String nameAndVersion) throws IOException {

        String repoInfo = "{'data':{'stagedRepositoryId':'" + repo + "','description':'Dropping " + nameAndVersion + "'}}";
        RequestBuilder builder = new RequestBuilder("POST");
        Request request = builder.setUrl("https://oss.sonatype.org/service/local/staging/profiles/" + profile + "/drop")
                         .addHeader("Content-Type", "application/json")
                         .addHeader("Authorization", "Basic " + authInfo)

                         .setBody(repoInfo.getBytes(OS.UTF_8))

                         .build();

        return sendHttpRequest(request);
    }

    /**
     * Deletes the extra .asc.md5 and .asc.sh1 'turds' that show-up when you upload the signature file. And yes, 'turds' is from sonatype
     * themselves. See: https://issues.sonatype.org/browse/NEXUS-4906
     * @throws IOException
     */
    private static
    void deleteSignatureTurds(final String authInfo, final String repo, final String groupId_asPath, final String name,
                              final String version, final File signatureFile)
                    throws IOException {

        String delURL = "https://oss.sonatype.org/service/local/repositories/" + repo + "/content/" +
                        groupId_asPath + "/" + name + "/" + version + "/" + signatureFile.getName();

        RequestBuilder builder;
        Request request;

        builder = new RequestBuilder("DELETE");
        request = builder.setUrl(delURL + ".sha1")
                         .addHeader("Authorization", "Basic " + authInfo)
                         .build();
        sendHttpRequest(request);

        builder = new RequestBuilder("DELETE");
        request = builder.setUrl(delURL + ".md5")
                         .addHeader("Authorization", "Basic " + authInfo)
                         .build();
        sendHttpRequest(request);
    }

    /**
     * Sends the HTTP request and returns the response
     * @throws IOException
     */
    private static
    String sendHttpRequest(final Request request) throws IOException {
        // we configure the client to use the DEFAULT JDK one, to lessen the number of dependencies required.
        final AsyncHttpClientConfig build = (new AsyncHttpClientConfig.Builder()).build();
        AsyncHttpClient client = new AsyncHttpClient(new JDKAsyncHttpProvider(build), build);

        final Response.ResponseBuilder builder2 = new Response.ResponseBuilder();
        final ListenableFuture done = client.executeRequest(request, new AsyncHandler() {
            @Override
            public
            void onThrowable(final Throwable throwable) {
                throwable.printStackTrace();
            }

            @Override
            public
            STATE onBodyPartReceived(final HttpResponseBodyPart httpResponseBodyPart) throws Exception {
                builder2.accumulate(httpResponseBodyPart);
                return STATE.CONTINUE;
            }

            @Override
            public
            STATE onStatusReceived(final HttpResponseStatus httpResponseStatus) throws Exception {
                builder2.accumulate(httpResponseStatus);
                return STATE.CONTINUE;
            }

            @Override
            public
            STATE onHeadersReceived(final HttpResponseHeaders httpResponseHeaders) throws Exception {
                builder2.accumulate(httpResponseHeaders);
                return STATE.CONTINUE;
            }

            @Override
            public
            Object onCompleted() throws Exception {
                return builder2.build();
            }
        });
        client.close();

        try {
            Response response = (Response) done.get();
            return response.getResponseBody();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }

        return "Error getting response";
    }

    /**
     * Creates the POM string.
     */
    private
    String createString(ProjectJava projectJava, final int targetJavaVersion, final Properties properties) {
        String dev_name = properties.getProperty("developerName");
        String dev_email = properties.getProperty("developerEmail");
        String dev_organization = properties.getProperty("developerOrganization");
        String dev_url = properties.getProperty("developerUrl");

        StringBuilder b = new StringBuilder();

        b.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                 "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                 "\t<modelVersion>4.0.0</modelVersion>");

        b.append(NL);
        b.append(NL);

        space(b,1).append("<groupId>").append(groupId).append("</groupId>").append(NL);
        space(b,1).append("<artifactId>").append(projectJava.name).append("</artifactId>").append(NL);
        space(b,1).append("<version>").append(version).append("</version>").append(NL);
        space(b,1).append("<packaging>").append("jar").append("</packaging>").append(NL);

        b.append(NL);

        space(b,1).append("<name>").append(projectJava.name).append("</name>").append(NL);
        space(b,1).append("<description>").append("Extremely fast, lightweight annotation scanner for the classpath, classloader, or specified location.").append("</description>").append(NL);
        space(b,1).append("<url>").append(githubUrl).append("</url>").append(NL);

        b.append(NL);
        b.append(NL);

        space(b,1).append("<issueManagement>").append(NL);
        space(b,2).append("<url>").append(githubUrl).append("/issues").append("</url>").append(NL);
        space(b,2).append("<system>").append("GitHub Issues").append("</system>").append(NL);
        space(b,1).append("</issueManagement>").append(NL);

        b.append(NL);
        b.append(NL);

        List<License> licenses = projectJava.getLicenses();
        License.sort(licenses);

        if (licenses != null) {
            space(b,1).append("<licenses>").append(NL);
            for (License license : licenses) {
                space(b,2).append("<license>").append(NL);

                space(b,3).append("<comments>").append(license.name).append("</comments>").append(NL);
                space(b,3).append("<name>").append(license.type.getDescription()).append("</name>").append(NL);
                space(b,3).append("<url>").append(license.type.getUrl()).append("</url>").append(NL);

                space(b,2).append("</license>").append(NL);
            }
            space(b,1).append("</licenses>").append(NL);
        }

        b.append(NL);
        b.append(NL);

        space(b,1).append("<developers>").append(NL);
        space(b,2).append("<developer>").append(NL);
        space(b,3).append("<name>").append(dev_name).append("</name>").append(NL);
        space(b,3).append("<email>").append(dev_email).append("</email>").append(NL);
        space(b,3).append("<organization>").append(dev_organization).append("</organization>").append(NL);
        space(b,3).append("<organizationUrl>").append(dev_url).append("</organizationUrl>").append(NL);
        space(b,2).append("</developer>").append(NL);
        space(b,1).append("</developers>").append(NL);

        b.append(NL);
        b.append(NL);

        space(b,1).append("<properties>").append(NL);
        space(b,2).append("<project.build.sourceEncoding>").append("UTF-8").append("</project.build.sourceEncoding>").append(NL);
        space(b,2).append("<maven.compiler.source>").append(targetJavaVersion).append("</maven.compiler.source>").append(NL);
        space(b,2).append("<maven.compiler.target>").append(targetJavaVersion).append("</maven.compiler.target>").append(NL);
        space(b,1).append("</properties>").append(NL);

        b.append(NL);
        b.append(NL);


        space(b,1).append("<scm>").append(NL);
        space(b,2).append("<connection>")
                             .append("scm:git:git@github.com:").append(gitHubParent).append('/').append(gitHubProject).append("</connection>").append(NL);

        space(b,2).append("<developerConnection>")
                             .append("scm:git:git@github.com:").append(gitHubParent).append('/').append(gitHubProject).append("</developerConnection>").append(NL);

        space(b,2).append("<url>")
                             .append("git@github.com:").append(gitHubParent).append('/').append(gitHubProject).append("</url>").append(NL);

        space(b,1).append("</scm>").append(NL);


        // have to add maven dependencies here
        if (!projectJava.dependencies.isEmpty()) {
            for (int i = 0; i < projectJava.dependencies.size(); i++) {
                Project<?> project = projectJava.dependencies.get(i);


            }

        }

//        <dependencies>
//          <dependency>
//               <groupId>junit</groupId>
//               <artifactId>junit</artifactId>
//               <version>4.8.2</version>
//               <scope>test</scope>
//          </dependency>
//        </dependencies>

        b.append("</project>").append(NL);

        return b.toString();
    }

    private static
    StringBuilder space(final StringBuilder b, final int spacer) {
        for (int i = 0; i < spacer; i++) {
            b.append("    ");
        }
        return b;
    }

    /**
     * Class to make uploading files less repetitive
     */
    private
    class Uploader {
        private final String name;
        private final String uploadURL;
        private final String repo;
        private final String authInfo;
        private final String groupId;
        private final String version;
        private final String description;

        public
        Uploader(final String name,
                 final String uploadURL,
                 final String repo,
                 final String authInfo,
                 final String groupId,
                 final String version,
                 final String description) {

            this.name = name;
            this.uploadURL = uploadURL;
            this.repo = repo;
            this.authInfo = authInfo;
            this.groupId = groupId;
            this.version = version;
            this.description = description;
        }

        public
        String upload(final File file, final String extension) throws IOException {
            return upload(file, extension, null);
        }

        public
        String upload(final File file, final String extension, String classification) throws IOException {

            final RequestBuilder builder = new RequestBuilder("POST");
            final RequestBuilder requestBuilder = builder.setUrl(uploadURL);
            requestBuilder.addHeader("Authorization", "Basic " + authInfo)

                          .addBodyPart(new StringPart("r", repo))
                          .addBodyPart(new StringPart("g", groupId))
                          .addBodyPart(new StringPart("a", name))
                          .addBodyPart(new StringPart("v", version))
                          .addBodyPart(new StringPart("p", "jar"))
                          .addBodyPart(new StringPart("e", extension))
                          .addBodyPart(new StringPart("desc", description));


            if (classification != null) {
                requestBuilder.addBodyPart(new StringPart("c", classification));
            }

            requestBuilder.addBodyPart(new FilePart("file", file));
            final Request request = requestBuilder.build();

            return sendHttpRequest(request);
        }
    }
}
