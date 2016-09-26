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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URL;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import org.bouncycastle.openpgp.PGPException;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.providers.jdk.JDKAsyncHttpProvider;
import com.ning.http.multipart.FilePart;
import com.ning.http.multipart.StringPart;

import ch.qos.logback.classic.Level;
import dorkbox.Build;
import dorkbox.Builder;
import dorkbox.build.util.BuildLog;
import dorkbox.license.License;
import dorkbox.license.LicenseType;
import dorkbox.util.Base64Fast;
import dorkbox.util.IO;
import dorkbox.util.OS;
import dorkbox.util.crypto.CryptoPGP;

/**
 * Creates pom files and exports the project to sonatype.org OSSRH.
 *
 * we use https://repo1.maven.org/maven2/com/ning/async-http-client/1.8.16/
 */
public
class MavenExporter {
    private static final String NL = OS.LINE_SEPARATOR;

    private static final String repositoryId = "repositoryId";
    private static final String profileId = "profileId";

    private static final int retryCount = 30;
    public static final String SPACER = "    ";

    private String groupId;
    private String projectVersion;

    private String gitHubUrl;
    private String gitHubParent;
    private String gitHubProject;

    private String propertyFile;
    private boolean keepOnServer = false;
    private ProjectJava project;

    static {
        // set the user-agent for NING. This is so it is clear who/what uploaded to sonatype
        // also set the version used to identify what builder version uploads the files to sonatype/maven
        System.setProperty(AsyncHttpClientConfig.class.getName() + "." + "userAgent", "JavaBuilder_" + Build.getVersion());
    }

    public
    MavenExporter(final MavenInfo info) {
        this.groupId = info.getGroupId();
        this.projectVersion = info.getVersion().toStringWithoutPrefix();
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
        this.gitHubUrl = githubUrl;
        this.gitHubParent = gitHubParent;
        this.gitHubProject = gitHubProject;

        return this;
    }

    void setProject(ProjectJava project) {
        this.project = project;
    }

    public
    ProjectJava credentials(final String propertyFile) {
        this.propertyFile = propertyFile;

        return project;
    }

    /**
     * This is called by the build.
     *
     * There is a limit of roughly 1024MB on any single file uploaded to OSSRH. Your uploads will fail with a broken pipe exception
     * when you hit this limit. Contact us directly if you need to upload larger components.
     */
    @SuppressWarnings("AccessStaticViaInstance")
    public
    void export() throws IOException {
        final int targetJavaVersion = project.targetJavaVersion;

        BuildLog.start();

        // make sure we have internet!
        URL maven = new URL("http://www.maven.org/");
        BufferedReader in = null;
        boolean hasInternet = false;
        try {
            in = new BufferedReader(new InputStreamReader(maven.openStream()));
            hasInternet = true;
        } catch (Exception ignored) {
        } finally {
            IO.closeQuietly(in);
        }


        if (!hasInternet) {
            BuildLog.title("MAVEN").println("Not able to connect to maven.org, aborting.");
            BuildLog.finish();
            return;
        }

        BuildLog.title("MAVEN").println("Exporting to sonatype");

        // TODO: maybe use straight java (ie: no libs?)
        // https://stackoverflow.com/questions/2793150/using-java-net-urlconnection-to-fire-and-handle-http-requests

        String fileName = project.name + "-" + projectVersion;
        fileName = new File(project.stagingDir, fileName).getAbsolutePath();


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
            IO.close(inStream);
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
            throw new RuntimeException("Only sonatype repo (OSSRH) supported at this time. Please create a github issue to support other " +
                                       "types.");
        }


        BuildLog.println("Creating the POM file");
        String pomFileText = createString(project, targetJavaVersion, properties);

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


        Builder.copyFile(project.outputFile.get(), jarFile);
        Builder.copyFile(project.outputFile.getSource(), sourcesFile);


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


        if (!project.shouldSaveBuild) {
            BuildLog.title("MAVEN").println("Not saving build and not exporting to maven.");
            BuildLog.finish();
            return;
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

        BuildLog.title("Uploading files").println();

        final String nameAndVersion = project.name + " v" + projectVersion;
        final String description = "Automated build of " + nameAndVersion + " by JavaBuilder";
        String uploadURL = "https://oss.sonatype.org/service/local/staging/upload";

        String hasErrors = "";
        String repo = "";
        String profile = "";

        RequestBuilder builder;
        Request request;
        int retryCount = MavenExporter.retryCount;


        Exception exception = null;

        // first we upload POM + main jar
        BuildLog.println(jarFile.getName());
        while (retryCount-- >= 0) {
            exception = null;

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

            hasErrors = sendHttp(request);

            if (hasErrors.contains("Access denied on repository ID=")) {
                // we have to delete this repo and RETRY.
                final String str = "Access denied on repository ID='";

                final int beginIndex = hasErrors.indexOf(str) + str.length();
                final int endIndex = hasErrors.indexOf("'", beginIndex + 1);
                repo = hasErrors.substring(beginIndex, endIndex);

                BuildLog.title("ERROR").println("Error uploading POM/JAR: '" + hasErrors + "'");
                BuildLog.println("Please login and verify errors for repo '" + repo + "'.");
            }

            final int repositoryIndex = hasErrors.lastIndexOf(repositoryId);
            final int profileIndex = hasErrors.lastIndexOf(profileId);

            try {
                repo = hasErrors.substring(repositoryIndex + repositoryId.length() + 3, hasErrors.length() - 2);
                profile = hasErrors.substring(profileIndex + profileId.length() + 3, repositoryIndex - 3);
                hasErrors = "";
                break;
            } catch (Exception e) {
                BuildLog.println("Error uploading POM/JAR: '" + hasErrors + "', retrying...");
                exception = e;
            }
        }

        if (retryCount == 0 || exception != null) {
            BuildLog.println();
            BuildLog.println("Error uploading POM/JAR: '" + hasErrors + "'");
            throw new RuntimeException("Unable to upload POM/JAR. Please login and verify errors.", exception);
        }



        final Uploader uploader = new Uploader(project.name, uploadURL, repo, authInfo, groupId, projectVersion, description);
        String groupID_asPath = groupId.replaceAll("\\.", "/");

        // now POM signature
        BuildLog.println(pomAscFile.getName());
        uploader.upload(pomAscFile, "pom.asc");
        deleteSignatureTurds(authInfo, repo, groupID_asPath, project.name, projectVersion, pomAscFile);



        // now jar signature
        BuildLog.println(jarAscFile.getName());
        uploader.upload(jarAscFile, "jar.asc");
        deleteSignatureTurds(authInfo, repo, groupID_asPath, project.name, projectVersion, jarAscFile);



        // now sources
        BuildLog.println(sourcesFile.getName());
        uploader.upload(sourcesFile, "jar", "sources");

        // now sources signature
        BuildLog.println(sourcesAscFile.getName());
        uploader.upload(sourcesAscFile, "jar.asc", "sources");
        deleteSignatureTurds(authInfo, repo, groupID_asPath, project.name, projectVersion, sourcesAscFile);



        // now javadoc
        BuildLog.println(docsFile.getName());
        uploader.upload(docsFile, "jar", "javadoc");


        // now javadoc signature
        BuildLog.println(docsAscFile.getName());
        uploader.upload(docsAscFile, "jar.asc", "javadoc");
        deleteSignatureTurds(authInfo, repo, groupID_asPath, project.name, projectVersion, docsAscFile);


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
            //
            //
            // WARNING: If the exact same file name exists (or the exact same version, if it's a snapshot) -- you will get a 401
            // authorization error. BUMP THE VERSION to solve this problem
            //
            //

            // CLOSE the repo

            //  this will also verify everything
            BuildLog.title("Closing Repo").print(".");
            closeRepo(authInfo, profile, repo, nameAndVersion);
            hasErrors = "";

            // now we have to make sure that the repo ACTUALLY is closed.
            retryCount = MavenExporter.retryCount;
            while (retryCount-- >= 0) {
                // get the status of the repo
                String act = activityForRepo(authInfo, repo);

                // act MUST contain this in order for this to proceed! (otherwise it didn't really close!)
                if (act.contains("<stagingActivity>\n    <name>close</name>\n") && act.contains("<name>repositoryClosed</name>")) {
                    // sometimes we get error 500, or something else -- so we need to retry. Only pass if we have something that
                    // KINDOF passes as valid XML
                    BuildLog.print(" Closed");
                    break;
                }
                else if (act.contains("repositoryCloseFailed")) {
                    hasErrors = act;
                } else {
                    BuildLog.print(".");
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
//                    hasErrors = "";
//                    closeRepo(authInfo, profile, repo, nameAndVersion);
                    // sometimes it takes a while for the system to verify and say that the repo has been closed.
                }
            }

            if (retryCount == 0) {
                BuildLog.println();

                if (!hasErrors.isEmpty()) {
                    BuildLog.println("Error for repo '" + repo + "' during close!'", hasErrors);
                }

                throw new RuntimeException("Error closing repo! Please log-in manually and correct the problem.");
            }


            BuildLog.title("Releasing Repo").print(".");

            // PROMOTE THE REPO

            // if we go too quickly, promoting will get this error:  {"errors":[{"id":"*","msg":"Unhandled: Repository: comdorkbox-1024 has invalid state: open"}]}
            // or:   {"errors":[{"id":"*","msg":"Unhandled: Repository: comdorkbox-1292 has invalid state: released"}]}
            // or will get a "already transitioning" error.
            hasErrors = promoteRepo(authInfo, profile, repo, nameAndVersion);


            retryCount = MavenExporter.retryCount;
            while (retryCount-- >= 0) {
                if (!hasErrors.isEmpty()) {
                    if (retryCount == 0) {
                        BuildLog.println("Unknown error during promotion (no more retries available)!", hasErrors);
                    }
                    else {
                        BuildLog.print(".");
                        try {
                            Thread.sleep(2000L);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                        if (!hasErrors.contains("has invalid state: open")) {
                            BuildLog.println("Unknown error during promotion, retrying!", hasErrors);
                        }

                        hasErrors = promoteRepo(authInfo, profile, repo, nameAndVersion);
                    }
                }
                else {
                    // have to verify that the URL exists
                    final String URL = "https://oss.sonatype.org/content/repositories/releases/" + groupID_asPath + "/" + project.name + "/" + projectVersion + "/";

                    if (hasRepoReleased(URL)) {
                        BuildLog.print(" Released");

                        BuildLog.title("Access URL")
                                .println(URL);

                        // NOW we drop the repo (since it was a success!
                        hasErrors = "Keeping repo on server";
                        if (!keepOnServer) {
                            BuildLog.println(" Success, dropping repo.");
                            hasErrors = dropRepo(authInfo, profile, repo, nameAndVersion);
                        }
                        else {
                            BuildLog.println(" Keeping repo anyways!");
                        }

                        if (!hasErrors.isEmpty()) {
                            BuildLog.println("'" + hasErrors + "'");
                        }
                        break;
                    }

                    BuildLog.print(".");
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    // sometimes it takes a while for the system to show that the repo has been released.
                }
            }

            if (retryCount == 0) {
                BuildLog.println();
                BuildLog.println("Error for repo '" + repo + "' during promotion to release! '" + hasErrors + "'");
                throw new RuntimeException("Error promoting to release! Please log-in manually and correct the problem for repo '" + repo
                                           + "'.");
            }
        }

        BuildLog.finish();
    }


//    /**
//     * Start the repo
//     * @throws IOException
//     */
//    private static
//    String startRepo(final String authInfo, final String profile, final String repo, final String nameAndVersion) throws IOException {
//
//        String repoInfo = "{'data':{'stagedRepositoryId':'" + repo + "','description':'Closing " + nameAndVersion + "'}}";
//        RequestBuilder builder = new RequestBuilder("POST");
//        Request request = builder.setUrl("https://oss.sonatype.org/service/local/staging/profiles/" + profile + "/start")
//                                 .addHeader("Content-Type", "application/json")
//                                 .addHeader("Authorization", "Basic " + authInfo)
//
//                                 .setBody(repoInfo.getBytes(OS.UTF_8))
//
//                                 .build();
//
//        return sendHttpRequest(request);
//    }

    /**
     * Gets the activity information for a repo. If there is a failure during verification/finish -- this will provide what it was.
     */
    private static
    String activityForRepo(final String authInfo, final String repo) throws IOException {

        RequestBuilder builder = new RequestBuilder("GET");
        Request request = builder.setUrl("https://oss.sonatype.org/service/local/staging/repository/" + repo + "/activity")
                                 .addHeader("Content-Type", "application/json")
                                 .addHeader("Authorization", "Basic " + authInfo)

                                 .build();

        return sendHttp(request);
    }

    /**
     * Checks to see if the repo has actually released. If it has NOT, this url will be a 404.
     */
    private static
    boolean hasRepoReleased(String url) throws IOException {

        RequestBuilder builder = new RequestBuilder("GET");
        Request request = builder.setUrl(url)
                                 .build();

        final String response = sendHttp(request);
        return !response.contains("<title>404 - Path");
    }

    /**
     * Closes the repo and (the server) will verify everything is correct.
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

        return sendHttp(request);
    }

    /**
     * Promotes (ie: release) the repo. Make sure to drop when done
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
        return sendHttp(request);
    }

    /**
     * Drops the repo
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

        return sendHttp(request);
    }

    /**
     * Deletes the extra .asc.md5 and .asc.sh1 'turds' that show-up when you upload the signature file. And yes, 'turds' is from sonatype
     * themselves. See: https://issues.sonatype.org/browse/NEXUS-4906
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
        sendHttp(request);

        builder = new RequestBuilder("DELETE");
        request = builder.setUrl(delURL + ".md5")
                         .addHeader("Authorization", "Basic " + authInfo)
                         .build();
        sendHttp(request);
    }

    /**
     * Sends the HTTP request and returns the response
     */
    private static
    String sendHttp(final Request request) throws IOException {
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
        } catch (ConnectException ignored) {
            return "Not connected or bad address";
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
    String createString(Project<?> project, final int targetJavaVersion, final Properties properties) {
        if (project.description == null) {
            throw new RuntimeException("Must specify a project description for project '" + project.name + "'  Aborting.");
        }

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
        space(b,1).append("<artifactId>").append(project.name).append("</artifactId>").append(NL);
        space(b,1).append("<version>").append(projectVersion).append("</version>").append(NL);
        space(b,1).append("<packaging>").append("jar").append("</packaging>").append(NL);

        b.append(NL);

        space(b,1).append("<name>").append(project.name).append("</name>").append(NL);
        space(b,1).append("<description>").append(project.description).append("</description>").append(NL);
        space(b,1).append("<url>").append(gitHubUrl).append("</url>").append(NL);

        b.append(NL);
        b.append(NL);

        space(b,1).append("<issueManagement>").append(NL);
        space(b,2).append("<url>").append(gitHubUrl).append("/issues").append("</url>").append(NL);
        space(b,2).append("<system>").append("GitHub Issues").append("</system>").append(NL);
        space(b,1).append("</issueManagement>").append(NL);

        b.append(NL);
        b.append(NL);

        List<License> licenses = project.getLicenses();
        License.sort(licenses);

        if (licenses != null) {
            space(b,1).append("<licenses>").append(NL);
            for (License license : licenses) {
                final boolean isCustomLicense = license.type.equals(LicenseType.CUSTOM);

                space(b,2).append("<license>").append(NL);

                if (!isCustomLicense) {
                    space(b,3).append("<comments>").append(license.name).append("</comments>").append(NL);
                }

                space(b,3).append("<name>").append(license.type.getDescription()).append("</name>").append(NL);

                final String url = license.type.getUrl();
                if (url != null && !url.isEmpty()) {
                    space(b, 3).append("<url>").append(url).append("</url>").append(NL);
                }

                if (isCustomLicense) {
                    final List<String> copyrights = license.copyrights;

                    if (copyrights.size() > 1) {
                        space(b, 3).append("<comments>").append(NL);
                        for (String copyright : copyrights) {
                            space(b, 4).append(License.fixSpace(copyright, SPACER, 4)).append(NL).append(NL);
                        }
                        space(b, 3).append("</comments>").append(NL);
                    }
                    else {
                        space(b, 3).append("<comments>").append(License.fixSpace(license.copyrights.get(0), SPACER, 3)).append("</comments>").append(NL);
                    }
                }

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


        b.append(NL);
        b.append(NL);

        // have to add maven dependencies here
        if (!project.dependencies.isEmpty()) {
            boolean header = false;

            for (int i = 0; i < project.dependencies.size(); i++) {
                Project<?> p = project.dependencies.get(i);
                final MavenInfo mavenInfo = p.mavenInfo;
                if (mavenInfo != null) {
                    if (!header) {
                        header = true;
                        space(b,1).append("<dependencies>").append(NL);
                    }

                    space(b,2).append("<dependency>").append(NL);
                    space(b,3).append("<groupId>").append(mavenInfo.getGroupId()).append("</groupId>").append(NL);
                    space(b,3).append("<artifactId>").append(mavenInfo.getArtifactId()).append("</artifactId>").append(NL);
                    space(b,3).append("<version>").append(mavenInfo.getVersion().toStringWithoutPrefix()).append("</version>").append(NL);

                    if (mavenInfo.getScope() != null) {
                        space(b,3).append("<scope>").append(mavenInfo.getScope()).append("</scope>").append(NL);
                    }
//                    space(b,3).append("<type>").append("pom").append("</type>").append(NL); // since it's a JAR, we ignore this.
                    space(b,2).append("</dependency>").append(NL);
                } else {
                    throw new IllegalArgumentException("Dependencies specified for project '" + p.name + "', but no maven info specified " +
                                                       "(it is required for export to maven)");
                }
            }

            if (header) {
                space(b,1).append("</dependencies>").append(NL);
            }
        }

        b.append("</project>").append(NL);

        return b.toString();
    }

    private static
    StringBuilder space(final StringBuilder b, final int spacer) {
        for (int i = 0; i < spacer; i++) {
            b.append(SPACER);
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

        String upload(final File file, final String extension) throws IOException {
            return upload(file, extension, null);
        }

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

            return sendHttp(request);
        }
    }
}
