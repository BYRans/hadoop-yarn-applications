package org.apache.hadoop.yarn.applications.distributedshell;

public class DshellArchive
{
    public String archivePath = "";
    public Long timestamp;
    public Long size;

    public String getArchivePath()
    {
        return this.archivePath;
    }

    public void setArchivePath(String archivePath) {
        this.archivePath = archivePath;
    }

    public Long getTimestamp() {
        return this.timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public Long getSize() {
        return this.size;
    }

    public void setSize(Long size) {
        this.size = size;
    }
}