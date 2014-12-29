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
package dorkbox;

public class BuildOptions {

    /**
     *  Options that affect the compilation of the project
     */
    public static class Compiler {
        /**
        * Do we want to force a rebuild of the project?
        */
        public boolean forceRebuild = false;

        /**
         * Will the compiled classes be DELETED when done with compiling (and possibly jar'ing their contents)?
         */
        public boolean deleteOnComplete = false;

        /**
         * Specifics for jar'ing the compiled classes
         */
        public Jar jar = new Jar();

        /**
        * enables PACK200+LZMA+ENCRYPTION on jar contents. Since this is
        * REALLY SLOW (creating and running), we don't always want to do this.
        */
        public boolean release = false;

        /**
        * we want debugging enabled (until release!)
        */
        public boolean debugEnabled = true;

        /**
         * if we are debug mode, do we want to disable certain actions to make compiling faster?
         */
        public boolean enableDebugSpeedImprovement = false;


        /**
        * Suppress sun warnings during the compile stage. ONLY enable this is you know what you are doing in your project!
        */
        public boolean suppressSunWarnings = false;

        /**
        * what version do we want to compile java for?
        *
        * when compiling for java 1.6, you MUST specify the 1.6 rt.jar location
        * Also, when compiling GWT, this has no effect
        */
        public int targetJavaVersion = 7;

        /**
        * this is only necessary when building for lesser versions of java than you are currently running
        * (for example, compiling for 1.6, when compiling on 1.7).
        * <p>
        * This is meant to be overridden for custom build locations
        */
        public CrossCompilerLibrary crossCompileLibrary = new CrossCompilerLibrary();


        /**
        * US export controls require that the JVM cannot perform AES-256 crypto. Here we are able to control the JCE policy files to
        * to permit unlimited crypto if we want to (and are following US export controls)
        */
        public boolean unlimitedJceCryptoRuntime = true;

        /**
        * Adds the "verbose" compile option. This is useful if you want to get a list (from the compiler) of EVERY CLASS compiled/used
        */
        public boolean enableCompilerTrace = false;



        /**
         * Specifics for jar'ing the compiled classes
         */
        public static class Jar {
            /**
             * Builds a jar from the specified source files, class file, and extras
             */
            public boolean buildJar;

            /**
             * Specify that all of the dates in the file should be now.
             */
            public boolean setDateLatest = false;

            /**
             * Should we include the source in the jar (alongside the class files)?
             */
            public boolean includeSource = false;

            /**
             * Should we include a zip of source files NEXT to the jar?
             * <p>
             * IE: xyz.jar + xyz-src.zip
             */
            public boolean includeSourceAsSeparate = false;

            /**
             * When ALSO creating a source jar, this lets you override the NAME (either relative or absolute) for the source file
             * <p>
             * The default is xyz-src.zip
             */
            public String sourceFilename = null;

            /**
             * Sign the jar with a self-signed certificate
             */
            public boolean signJar;

            /**
             * What name will be used for signing the jar?
             */
            public String signName;
        }

        /**
         * Provide the location of the rt.jar libraries for 'cross compiling' to a different java target.
         * <p>
         * This is meant to be overridden for custom locations.
         */
        public static class CrossCompilerLibrary {
            public CrossCompilerLibrary() {
            }

            /** Please note that the binary release is GLPv2 + Classpath Exception, giving us permission to use it to compile binaries */
            public String getCrossCompileLibraryLocation(int targetVersion) {
                return Build.path("libs", "jdkRuntimes", "openJdk" + targetVersion + "_rt.jar");
            }
        }
    }

    public Compiler compiler = new Compiler();

    /**
     * @return the target java version to compile, in full format. IE: "1.6", or "1.7"
     */
    public String getTargetVersion() {
        return "1."+this.compiler.targetJavaVersion;
    }
}
