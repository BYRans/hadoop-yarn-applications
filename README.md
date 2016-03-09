# hadoop-yarn-applications

* [1 概述](#1)
* [2 YARN DistributedShell不能满足当前需求](#2)
    * [2.1 功能需求](#21)
    * [2.2 YARN DistributedShell对需求的支持情况](#22)
    * [2.3 需要对YARN DistributedShell进行的修改](#23)
* [3 YARN DistributedShell源码获取](#3)
* [4 YARN DistributedShell源码分析及修改](#4)
    * [4.1 Client类](#41)
        * [4.1.1 Client源码逻辑](#411)
        * [4.1.2 对Client源码的修改](#412)
    * [4.2 ApplicationMaster类](#42)
        * [4.2.1 ApplicationMaster源码逻辑](#421)
        * [4.2.2 对ApplicationMaster源码的修改](#422)
    * [4.3 DSConstants类](#43)
    * [4.4 Log4jPropertyHelper类](#44)


<span id="1"></span>
# 1 概述
Hadoop YARN项目自带一个非常简单的应用程序编程实例--**DistributedShell**。DistributedShell是一个构建在YARN之上的non-MapReduce应用示例。它的主要功能是在Hadoop集群中的多个节点，并行执行用户提供的shell命令或shell脚本（将用户提交的一串shell命令或者一个shell脚本，由ApplicationMaster控制，分配到不同的container中执行)。

<span id="2"></span>
# 2 YARN DistributedShell不能满足当前需求
<span id="21"></span>
## 2.1 功能需求
我所参与的项目通过融合Hive、MapReduce、Spark、Kafka等大数据开源组件，搭建了一个数据分析平台。
平台需要新增一个功能：

* 在集群中选取一个节点，执行用户提交的jar包。
* 该功能需要与平台已有的基于Hive、MR、Spark实现的业务以及YARN相融合。
* 简而言之，经分析与调研，我们需要基于YARN的DistributedShell实现该功能。

该功能需要实现：

* 单机执行用户自己提交的jar包
* 用户提交的jar包会有其他jar包的依赖
* 用户提交的jar包只能选取一个节点运行
* 用户提交的jar包需要有缓存数据的目录

<span id="22"></span>
## 2.2 YARN DistributedShell对需求的支持情况
YARN的DistributedShell功能为：

* 支持执行用户提供的shell命令或脚本
* 执行节点数可以通过参数num_containers设置，默认值为1
* 不支持jar包的执行
* 更不支持依赖包的提交
* 不支持jar包缓存目录的设置

<span id="23"></span>
## 2.3 需要对YARN DistributedShell进行的修改
* 增加支持执行jar包功能
* 增加支持缓存目录设置功能
* 删除执行节点数设置功能，不允许用户设置执行节点数，将执行节点数保证值为1

<span id="3"></span>
# 3 YARN DistributedShell源码获取
YARN DistributedShell源码可以在GitHub上apache/hadoop获取，hadoop repository中DistributedShell的源代码路径为:
`hadoop/hadoop-yarn-project/hadoop-yarn/hadoop-yarn-applications/hadoop-yarn-applications-distributedshell/src/main/java/org/apache/hadoop/yarn/applications/distributedshell/`
这里修改的是2.6.0版本源码。

<span id="4"></span>
# 4 YARN DistributedShell源码分析及修改
YARN DistributedShell包含4个java Class：

```xml
DistributedShell
    ├── Client.java
    ├── ApplicationMaster.java
    ├── DSConstants.java
    ├── Log4jPropertyHelper.java
```

- **Client**：客户端提交application
- **ApplicationMaster**：注册AM，申请分配container，启动container
- **DSConstants**：Client类和ApplicationMaster类中的常量定义
- **Log4jPropertyHelper**：加载Log4j配置

<span id="41"></span>
## 4.1 Client类
<span id="411"></span>
### 4.1.1 Client源码逻辑
Client类是DistributedShell应用提交到YARN的客户端。Client将启动application master，然后application master启动多个containers用于运行shell命令或脚本。Client运行逻辑为：

 1. 使用ApplicationClientProtocol协议连接ResourceManager（也叫ApplicationsMaster或ASM），获取一个新的ApplicationId。（ApplicationClientProtocol提供给Client一个获取集群信息的方式）
 2. 在一个job提交过程中，Client首先创建一个ApplicationSubmissionContext。ApplicationSubmissionContext定义了application的详细信息，例如：ApplicationId、application name、application分配的优先级、application分配的队列。另外，ApplicationSubmissionContext还定义了一个Container，该Container用于启动ApplicationMaster。
 3. 在ContainerLaunchContext中需要初始化启动ApplicationMaster的资源：
    * 运行ApplicationMaster的container的资源
    * jars（例：AppMaster.jar）、配置文件(例：log4j.properties)
    * 运行环境（例：hadoop特定的类路径、java classpath）
    * 启动ApplicationMaster的命令
 4. Client使用ApplicationSubmissionContext提交application到ResourceManager，并通过按周期向ResourceManager请求ApplicationReport，完成对applicatoin的监控。
 5. 如果application运行时间超过timeout的限制（默认为600000毫秒，可通过-timeout进行设置），client将发送KillApplicationRequest到ResourceManager，将application杀死。

具体代码如下(基于YARN2.6.0)：

* Cilent的入口main方法：

```java
public static void main(String[] args) {
        boolean result = false;
        try {
            DshellClient client = new DshellClient();
            LOG.info("Initializing Client");
            try {
                boolean doRun = client.init(args);
                if (!doRun) {
                    System.exit(0);
                }
            } catch (IllegalArgumentException e) {
                System.err.println(e.getLocalizedMessage());
                client.printUsage();
                System.exit(-1);
            }
            result = client.run();
        } catch (Throwable t) {
            LOG.fatal("Error running Client", t);
            System.exit(1);
        }
        if (result) {
            LOG.info("Application completed successfully");
            System.exit(0);
        }
        LOG.error("Application failed to complete successfully");
        System.exit(2);
    }
```

main方法：

* 输入参数为用户CLI的执行命令，例如：`hadoop jar hadoop-yarn-applications-distributedshell-2.0.5-alpha.jar org.apache.hadoop.yarn.applications.distributedshell.Client -jar hadoop-yarn-applications-distributedshell-2.0.5-alpha.jar -shell_command '/bin/date' -num_containers 10`,该命令提交的任务为：启动10个container，每个都执行`date`命令。
* main方法将运行init方法，如果init方法返回true则运行run方法。
* init方法解析用户提交的命令，解析用户命令中的参数值。
* run方法将完成Client源码逻辑中描述的功能。

<span id="412"></span>
### 4.1.2 对Client源码的修改
在原有YARN DistributedShell的基础上做的修改如下：

* 在CLI为用户增加了`container_files`和`container_archives`两个参数
    * `container_files`指定用户要执行的jar包的依赖包，多个依赖包以逗号分隔
    * `container_archives`指定用户执行的jar包的缓存目录，多个目录以逗号分隔
* 删除`num_containers`参数
    * 不允许用户设置container的个数，使用默认值1

对Client源码修改如下：

* 变量
    * 增加变量用于保存`container_files`和`container_archives`两个参数的值

```java
// 增加两个变量，保存container_files、container_archives的参数值↓↓↓↓↓↓↓
private String[] containerJarPaths = new String[0];
private String[] containerArchivePaths = new String[0];
// ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
```


* Client构造方法
    * 删除num_containers参数的初试化，增加`container_files`和`container_archives`两个参数
    * 修改构造方法的ApplicationMaster类

```java
// 删除num_containers项，不允许用户设置containers个数，containers个数默认为1 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
//opts.addOption("num_containers", true, "No. of containers on which the shell command needs to be executed");
// ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
// 添加container_files、container_archives的描述↓↓↓↓↓↓↓↓↓↓↓↓↓↓
this.opts.addOption("container_files", true,"The files that containers will run .  Separated by comma");
this.opts.addOption("container_archives", true,"The archives that containers will unzip.  Separated by comma");
// ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
```

```java
public DshellClient(Configuration conf) throws Exception {
        // 修改构造方法的ApplicationMaster类↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
        this("org.apache.hadoop.yarn.applications.distributedshell.DshellApplicationMaster",conf);
        // ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
    }
```

* init方法
    * 增加`container_files`和`container_archives`两个参数的解析

```java
// 初始化选项container_files、container_archives↓↓↓↓↓↓↓
this.opts.addOption("container_files", true,"The files that containers will run .  Separated by comma");
this.opts.addOption("container_archives", true,"The archives that containers will unzip.  Separated by comma");
// ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
```

* run方法
    * 上传`container_files`和`container_archives`两个参数指定的依赖包和缓存目录至HDFS

```java
 // 上传container_files指定的jar包到HDFS ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
if (this.containerJarPaths.length != 0)
    for (int i = 0; i < this.containerJarPaths.length; i++) {
        String hdfsJarLocation = "";
        String[] jarNameSplit = this.containerJarPaths[i].split("/");
        String jarName = jarNameSplit[(jarNameSplit.length - 1)];

        long hdfsJarLen = 0L;
        long hdfsJarTimestamp = 0L;
        if (!this.containerJarPaths[i].isEmpty()) {
            Path jarSrc = new Path(this.containerJarPaths[i]);
            String jarPathSuffix = this.appName + "/" + appId.toString() +
                    "/" + jarName;
            Path jarDst = new Path(fs.getHomeDirectory(), jarPathSuffix);
            fs.copyFromLocalFile(false, true, jarSrc, jarDst);
            hdfsJarLocation = jarDst.toUri().toString();
            FileStatus jarFileStatus = fs.getFileStatus(jarDst);
            hdfsJarLen = jarFileStatus.getLen();
            hdfsJarTimestamp = jarFileStatus.getModificationTime();
            env.put(DshellDSConstants.DISTRIBUTEDJARLOCATION + i,
                    hdfsJarLocation);
            env.put(DshellDSConstants.DISTRIBUTEDJARTIMESTAMP + i,
                    Long.toString(hdfsJarTimestamp));
            env.put(DshellDSConstants.DISTRIBUTEDJARLEN + i,
                    Long.toString(hdfsJarLen));
        }
    }
// ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
// 上传container_archives到HDFS↓↓↓↓↓↓↓↓↓↓↓↓↓↓
long hdfsArchiveLen;
String archivePathSuffix;
Path archiveDst;
FileStatus archiveFileStatus;
if (this.containerArchivePaths.length != 0) {
    for (int i = 0; i < this.containerArchivePaths.length; i++) {
        String hdfsArchiveLocation = "";
        String[] archiveNameSplit = this.containerArchivePaths[i].split("/");
        String archiveName = archiveNameSplit[(archiveNameSplit.length - 1)];
        hdfsArchiveLen = 0L;
        long hdfsArchiveTimestamp = 0L;
        if (!this.containerArchivePaths[i].isEmpty()) {
            Path archiveSrc = new Path(this.containerArchivePaths[i]);
            archivePathSuffix = this.appName + "/" + appId.toString() +
                    "/" + archiveName;
            archiveDst = new Path(fs.getHomeDirectory(),
                    archivePathSuffix);
            fs.copyFromLocalFile(false, true, archiveSrc, archiveDst);
            hdfsArchiveLocation = archiveDst.toUri().toString();
            archiveFileStatus = fs.getFileStatus(archiveDst);
            hdfsArchiveLen = archiveFileStatus.getLen();
            hdfsArchiveTimestamp = archiveFileStatus
                    .getModificationTime();
            env.put(DshellDSConstants.DISTRIBUTEDARCHIVELOCATION + i,
                    hdfsArchiveLocation);
            env.put(DshellDSConstants.DISTRIBUTEDARCHIVETIMESTAMP + i,
                    Long.toString(hdfsArchiveTimestamp));
            env.put(DshellDSConstants.DISTRIBUTEDARCHIVELEN + i,
                    Long.toString(hdfsArchiveLen));
        }
    }
}
// ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
```

<span id="42"></span>
## 4.2 ApplicationMaster类
<span id="421"></span>
### 4.2.1 ApplicationMaster源码逻辑
一个ApplicationMaster将在启动一个或过个container，在container上执行shell命令或脚本。ApplicationMaster运行逻辑为：

 1. ResourceManager启动一个container用于运行ApplicationMaster。
 2. ApplicationMaster连接ResourceManager，向ResourceManager注册自己。
    * 向ResourceManager注册的信息有：
        * ApplicationMaster的ip:port
        * ApplicationMaster所在主机的hostname
        * ApplicationMaster的tracking url。客户端可以用tracking url来跟踪任务的状态和历史记录。
    * 需要注意的是：在DistributedShell中，不需要初注册tracking url和 appMasterHost:appMasterRpcPort，只需要设置hostname。
 3. ApplicationMaster会按照设定的时间间隔向ResourceManager发送心跳。ResourceManager的ApplicationMasterService每次收到ApplicationMaster的心跳信息后，会同时在AMLivelinessMonitor更新其最近一次发送心跳的时间。
 4. ApplicationMaster通过ContainerRequest方法向ResourceManager发送请求，申请相应数目的container。在发送申请container请求前，需要初始化Request，需要初始化的参数有：
    * Priority：请求的优先级
    * capability：当前支持CPU和Memory
    * nodes：申请的container所在的host（如果不需要指定，则设为null）
    * racks：申请的container所在的rack（如果不需要指定，则设为null）
 5. ResourceManager返回ApplicationMaster的申请的containers信息，根据container的状态-containerStatus，更新已申请成功和还未申请的container数目。
 6. 申请成功的container，ApplicationMaster则通过ContainerLaunchContext初始化container的启动信息。初始化container后启动container。需要初始化的信息有：
    * Container id
    * 执行资源（Shell脚本或命令、处理的数据）
    * 运行环境
    * 运行命令
 7. container运行期间，ApplicationMaster对container进行监控。
 8. job运行结束，ApplicationMaster发送FinishApplicationMasterRequest请求给ResourceManager，完成ApplicationMaster的注销。

具体代码如下(基于YARN2.6.0)：

* ApplicationMaster的入口main方法：

```java
public static void main(String[] args) {
       boolean result = false;
       try {
           DshellApplicationMaster appMaster = new DshellApplicationMaster();
           LOG.info("Initializing ApplicationMaster");
           boolean doRun = appMaster.init(args);
           if (!doRun) {
               System.exit(0);
           }
           appMaster.run();
           result = appMaster.finish();
       } catch (Throwable t) {
           LOG.fatal("Error running ApplicationMaster", t);
           LogManager.shutdown();
           ExitUtil.terminate(1, t);
       }
       if (result) {
           LOG.info("Application Master completed successfully. exiting");
           System.exit(0);
       } else {
           LOG.info("Application Master failed. exiting");
           System.exit(2);
       }
   }
```

main方法：

* 输入参数为Client提交的执行命令。
* init方法完成对执行命令的解析，获取执行命令中参数指定的值。
* run方法完成ApplicationMaster的启动、注册、containers的申请、分配、监控等功能的启动。
    * run方法中建立了与ResourceManager通信的Handle-**AMRMClientAsync**，其中的CallbackHandler是由RMCallbackHandler类实现的。
        * RMCallbackHandler类中实现了containers的申请、分配等方法。
        * containers的分配方法onContainersAllocated中通过LaunchContainerRunnable类中run方法完成container的启动。
* finish方法完成container的停止、ApplicationMaster的注销。

<span id="422"></span>
### 4.2.2 对ApplicationMaster源码的修改
在原有YARN DistributedShell的基础上做的修改如下：

* 在ApplicationMaster初试化时，增加对`container_files`和`container_archives`两个参数指定值的支持。即：初始化`container_files`和`container_archives`指定的运行资源在HDFS上的信息。
* 在container运行时，从HDFS上加载`container_files`和`container_archives`指定的资源。

对ApplicationMaster源码修改如下：

* 变量
    * 增加变量，用于保存`container_files`和`container_archives`指定的运行资源在HDFS上的信息。

```java
// 增加container_files、container_archives选项值变量 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
private ArrayList<DshellFile> scistorJars = new ArrayList();
private ArrayList<DshellArchive> scistorArchives = new ArrayList();
// ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
```
    
* ApplicationMaster的init方法
    * 初始化`container_files`和`container_archives`两个参数指定值信息。

```java
// 遍历envs，把所有的jars、archivers的HDFS路径，时间戳，LEN全部保存到jarPaths对象数组中 ↓↓↓↓↓↓↓↓↓↓
for (String key : envs.keySet()) {
    if (key.contains(DshellDSConstants.DISTRIBUTEDJARLOCATION)) {
        DshellFile scistorJar = new DshellFile();
        scistorJar.setJarPath((String) envs.get(key));
        String num = key
                .split(DshellDSConstants.DISTRIBUTEDJARLOCATION)[1];
        scistorJar.setTimestamp(Long.valueOf(Long.parseLong(
                (String) envs
                        .get(DshellDSConstants.DISTRIBUTEDJARTIMESTAMP + num))));
        scistorJar.setSize(Long.valueOf(Long.parseLong(
                (String) envs
                        .get(DshellDSConstants.DISTRIBUTEDJARLEN + num))));
        this.scistorJars.add(scistorJar);
    }
}

for (String key : envs.keySet()) {
    if (key.contains(DshellDSConstants.DISTRIBUTEDARCHIVELOCATION)) {
        DshellArchive scistorArchive = new DshellArchive();
        scistorArchive.setArchivePath((String) envs.get(key));
        String num = key
                .split(DshellDSConstants.DISTRIBUTEDARCHIVELOCATION)[1];
        scistorArchive.setTimestamp(Long.valueOf(Long.parseLong(
                (String) envs
                        .get(DshellDSConstants.DISTRIBUTEDARCHIVETIMESTAMP +
                                num))));
        scistorArchive.setSize(Long.valueOf(Long.parseLong(
                (String) envs
                        .get(DshellDSConstants.DISTRIBUTEDARCHIVELEN + num))));
        this.scistorArchives.add(scistorArchive);
    }
}
// ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
```

* LaunchContainerRunnable的run方法（container线程的run方法）
    * 从HDFS上加载`container_files`和`container_archives`指定的资源。

```java
// 把HDFS中的jar、archive加载到container的LocalResources，也就是从HDFS分发到container节点的过程 ↓↓↓↓↓↓↓↓↓↓↓↓↓
for (DshellFile perJar : DshellApplicationMaster.this.scistorJars) {
    LocalResource jarRsrc = (LocalResource) Records.newRecord(LocalResource.class);
    jarRsrc.setType(LocalResourceType.FILE);
    jarRsrc.setVisibility(LocalResourceVisibility.APPLICATION);
    try {
        jarRsrc.setResource(
                ConverterUtils.getYarnUrlFromURI(new URI(perJar.getJarPath()
                        .toString())));
    } catch (URISyntaxException e1) {
        DshellApplicationMaster.LOG.error("Error when trying to use JAR path specified in env, path=" +
                perJar.getJarPath(), e1);
        DshellApplicationMaster.this.numCompletedContainers.incrementAndGet();
        DshellApplicationMaster.this.numFailedContainers.incrementAndGet();
        return;
    }
    jarRsrc.setTimestamp(perJar.getTimestamp().longValue());
    jarRsrc.setSize(perJar.getSize().longValue());
    String[] tmp = perJar.getJarPath().split("/");
    localResources.put(tmp[(tmp.length - 1)], jarRsrc);
}
String[] tmp;
for (DshellArchive perArchive : DshellApplicationMaster.this.scistorArchives) {
    LocalResource archiveRsrc =
            (LocalResource) Records.newRecord(LocalResource.class);
    archiveRsrc.setType(LocalResourceType.ARCHIVE);
    archiveRsrc.setVisibility(LocalResourceVisibility.APPLICATION);
    try {
        archiveRsrc.setResource(
                ConverterUtils.getYarnUrlFromURI(new URI(perArchive
                        .getArchivePath().toString())));
    } catch (URISyntaxException e1) {
        DshellApplicationMaster.LOG.error("Error when trying to use ARCHIVE path specified in env, path=" +
                        perArchive.getArchivePath(),
                e1);
        DshellApplicationMaster.this.numCompletedContainers.incrementAndGet();
        DshellApplicationMaster.this.numFailedContainers.incrementAndGet();
        return;
    }
    archiveRsrc.setTimestamp(perArchive.getTimestamp().longValue());
    archiveRsrc.setSize(perArchive.getSize().longValue());
    tmp = perArchive.getArchivePath().split("/");
    String[] tmptmp = tmp[(tmp.length - 1)].split("[.]");
    localResources.put(tmptmp[0], archiveRsrc);
}
// ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
```

<span id="43"></span>
## 4.3 DSConstants类
DSConstants类中是在Client和ApplicationMaster中的常量，对DSConstants类的修改为：增加了container_files、container_archives相关常量。修改代码如下：

```java
// 增加container_files、container_archives相关常量 ↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓↓
public static final String DISTRIBUTEDJARLOCATION = "DISTRIBUTEDJARLOCATION";
public static final String DISTRIBUTEDJARTIMESTAMP = "DISTRIBUTEDJARTIMESTAMP";
public static final String DISTRIBUTEDJARLEN = "DISTRIBUTEDJARLEN";

public static final String DISTRIBUTEDARCHIVELOCATION = "DISTRIBUTEDARCHIVELOCATION";
public static final String DISTRIBUTEDARCHIVETIMESTAMP = "DISTRIBUTEDARCHIVETIMESTAMP";
public static final String DISTRIBUTEDARCHIVELEN = "DISTRIBUTEDARCHIVELEN";
// ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
```

<span id="44"></span>
## 4.4 Log4jPropertyHelper类
对Log4jPropertyHelper类无任何改动。
