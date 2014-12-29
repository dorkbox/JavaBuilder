package dorkbox.build.util.jar;

import java.io.File;

public class SortedFiles implements Comparable<SortedFiles> {
    public String fileName;
    public File file;

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.fileName == null ? 0 : this.fileName.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        SortedFiles other = (SortedFiles) obj;
        if (this.fileName == null) {
            if (other.fileName != null) {
                return false;
            }
        } else if (!this.fileName.equals(other.fileName)) {
            return false;
        }
        return true;
    }

    @Override
    public int compareTo(SortedFiles o) {
        return this.fileName.compareTo(o.fileName);
    }
}
