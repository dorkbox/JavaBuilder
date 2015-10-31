package dorkbox.build.util;

import com.esotericsoftware.wildcard.Paths;

public
class CrossCompileClass {
    public final int targetJavaVersion;
    public final Paths sourceFiles;

    public
    CrossCompileClass(final int targetJavaVersion, final Paths sourceFiles) {
        this.targetJavaVersion = targetJavaVersion;
        this.sourceFiles = sourceFiles;
    }
}
