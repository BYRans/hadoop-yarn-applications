package org.apache.hadoop.yarn.applications.distributedshell;

public class DshellFile
{
    public String jarPath = "";
    public Long timestamp;
    public Long size;

    public String getJarPath()
    {
        return this.jarPath;
    }

    public void setJarPath(String jarPath) {
        this.jarPath = jarPath;
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