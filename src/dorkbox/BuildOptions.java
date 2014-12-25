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
        * enables PACK200+LZMA+GZIP+ENCRYPTION on jar contents. Since this is
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
        public boolean unlimitedJceCrpytoRuntime = true;

        /**
        * Adds the "verbose" compile option. This is useful if you want to get a list (from the compiler) of EVERY CLASS compiled/used
        */
        public boolean enableCompilerTrace = false;

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

    /**
     *  Options that affect how the launcher is included, and how the jar is signed/encrypted
     */
    public static class Launcher {
        /**
        * do we want to enable the launcher crypto signature verification? (runtime requires this)
        */
        public boolean crypto = true;

        /**
        * do we want to deploy the JAVA runtime as a part of our app? (depends on crypto to work)
        */
        public boolean runtime = true;

        /**
        * do we want to enable the key/mouse input monitor?
        */
        public boolean monitor = true;

        /**
        * do we want to enable the socket bind wrapper? (when you run launcher as root, it will drop root when runnning java)
        */
        public boolean bindWrapper = true;

        /**
        * do we want to enable LGPL parsing of the RESOURCES.BOX file?
        */
        public boolean lpgl = false;
    }

    /**
     *  Misc libraries to include (which are not easy to just link the library)
     */
    public static class Misc {
        /**
        * Java7 (but not ARM) can have the optional JavaFX library included.
        */
        public boolean includeJavaFx = false;

    }

    public Compiler compiler = new Compiler();
    public Launcher launcher = new Launcher();
    public Misc misc = new Misc();




    /**
     * Gets the executable name based on what different build options are specified.
     */
    public String getExecutableName(String exectuableBaseName) {
        if (this.launcher.runtime && !this.launcher.crypto) {
            throw new RuntimeException("Unable to deply runtime with crypto disabled! You must enable crypto to continue!");
        }

        String newName = exectuableBaseName;
        if (this.compiler.debugEnabled) {
            newName += "_debug";
        }
        if (this.launcher.lpgl) {
            newName += "_lgpl";
        }
        if (this.launcher.crypto) {
            newName += "_crypto";
        }
        if (this.launcher.runtime) {
            newName += "_runtime";
        }
        if (this.launcher.monitor) {
            newName += "_monitor";
        }
        if (this.launcher.bindWrapper) {
            newName += "_bind";
        }

        return newName;
    }

    /**
     * @return the target java version to compile, in full format. IE: "1.6", or "1.7"
     */
    public String getTargetVersion() {
        return "1."+this.compiler.targetJavaVersion;
    }
}
