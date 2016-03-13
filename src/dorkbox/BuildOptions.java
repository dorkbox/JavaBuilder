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

public
class BuildOptions {

    public Compiler compiler = new Compiler();

    /**
     * Options that affect the compilation of the project
     */
    public static
    class Compiler {
        /**
         * Do we want to force a rebuild of the project?
         */
        public boolean forceRebuild = false;

        /**
         * Do we want to save the build hashes? (used to determine if a rebuild is necessary)
         */
        public boolean saveBuild = true;

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
         * this is only necessary when building for lesser versions of java than you are currently running
         * (for example, compiling for 1.6, when compiling on 1.7).
         * <p/>
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
         * Provide the location of the rt.jar libraries for 'cross compiling' to a different java target.
         * <p/>
         * This is meant to be overridden for custom locations.
         */
        public static
        class CrossCompilerLibrary {
            public
            CrossCompilerLibrary() {
            }

            /**
             * Please note that the binary release is GLPv2 + Classpath Exception, giving us permission to use it to compile binaries.
             * If the file ends in .lzma.pack, the system will automatically unpack/un-lzma the file.
             */
            public
            String getCrossCompileLibraryLocation(int targetVersion) {
                return Builder.path("libs", "jdkRuntimes", "openJdk" + targetVersion + "_rt.jar");
            }
        }
    }
}
