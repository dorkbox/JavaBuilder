package dorkbox.license;

public class LicenseWrapper implements Comparable<LicenseWrapper>{
    public LicenseType license;
    public byte[] bytes;

    public LicenseWrapper(LicenseType license, byte[] bytes) {
        this.license = license;
        this.bytes = bytes;
    }

    @Override
    public int compareTo(LicenseWrapper o) {
        return this.license.getExtension().compareTo(o.license.getExtension());
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.license == null ? 0 : this.license.hashCode());
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
        LicenseWrapper other = (LicenseWrapper) obj;
        if (this.license != other.license) {
            return false;
        }
        return true;
    }
}
