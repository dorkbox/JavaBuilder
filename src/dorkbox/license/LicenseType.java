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
    APACHE("Apachev2", "Apache 2.0 License", "http://opensource.org/licenses/Apache-2.0"),
    BSD2("BSD2", "BSD 2-clause License", "http://opensource.org/licenses/BSD-2-Clause"), // Same as "Simplified" or "FreeBSD" license
    BSD3("BSD3", "BSD 3-clause License", "http://opensource.org/licenses/BSD-3-Clause"), // same as 'New' or 'Revised' license
    CC0("CC0", "CC0 License", "https://creativecommons.org/publicdomain/zero/1.0/"),
    EPL("EPLv1", "Eclipse Public License", "http://opensource.org/licenses/EPL-1.0"),
    GPLv2_CP("GPLv2_CP", "GPL v2 License, with Classpath exception", "https://www.gnu.org/software/classpath/license.html"),
    LGPLv2("LGPLv2.1", "LGPL v2.1 License", "http://opensource.org/licenses/LGPL-2.1"),
    LGPLv3("LGPLv3", "LGPL v3 License", "http://opensource.org/licenses/LGPL-3.0"),
    MIT("MIT", "MIT License", "http://www.opensource.org/licenses/mit-license.php"), // same as MIT X11
    MPL("MPLv1.1", "Mozilla Public License 1.1", "http://opensource.org/licenses/MPL-1.1"), // replaced by MPL2.0
    MPL2("MPLv2.0", "Mozilla Public License 2.0", "http://opensource.org/licenses/MPL-2.0"),
    OFL("OFLv1.1", "SIL Open Font License", "http://opensource.org/licenses/OFL-1.1"),
    PUBLIC("Public", "Public Domain", ""),  // not quite the same as CC0 (CC0 is better)
    ;

    private final String name;
    private final String description;
    private final String url;

    LicenseType(String name, String description, String url) {
        this.name = name;
        this.description = description;
        this.url = url;
    }

    /** This is the file name extension for the license file */
    public String getExtension() {
        return this.name;
    }

    public String getDescription() {
        return this.description;
    }

    public
    String getUrl() {
        return url;
    }
}
