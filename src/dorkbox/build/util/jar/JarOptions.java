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
package dorkbox.build.util.jar;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.esotericsoftware.wildcard.Paths;

import dorkbox.license.License;

public class JarOptions {
    public File  outputFile = null;
    public Paths inputPaths = null;

    public String  mainClass  = null;
    public Map<String,String>  otherManifestAttributes = new LinkedHashMap<String, String>();

    public Paths   classpath  = null;

    /** target dir + paths for extra files **/
    public Paths extraPaths;

    /** Include the source code if requested **/
    public Paths sourcePaths;

    /** Include the various licenses if possible **/
    public List<License> licenses;

    /**
     * Specify that all of the dates in the file should be now.
     */
    public boolean setDateLatest;
}
