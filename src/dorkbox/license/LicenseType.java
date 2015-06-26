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
package dorkbox.license;

public enum LicenseType {
    APACHE("Apachev2", "Apache 2.0 License"),
    BSD("BSD", "BSD License"), // same as New BSD
    CC0("CC0", "CC0 License"),
    EPL("EPLv1", "Eclipse Public License"),
    GPLv2_CP("GPLv2_CP", "GPL v2 License, with Classpath exception"),
    LGPLv2("LGPLv2.1", "LGPL v2.1 License"),
    LGPLv3("LGPLv3", "LGPL v3 License"),
    MIT("MIT", "MIT License"), // same as MIT X11
    MPL("MPLv1.1", "Mozilla Public License 1.1"),
    MPL2("MPLv2.0", "Mozilla Public License 2.0"),
    OFL("OFLv1.1", "SIL Open Font License"),
    PUBLIC("Public", "Public Domain"),  // not quite the same as CC0 (CC0 is better)
    ;

    private final String name;
    private final String description;

    private LicenseType(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /** This is the file name extension for the license file */
    public String getExtension() {
        return this.name;
    }

    public String getDescription() {
        return this.description;
    }
}
