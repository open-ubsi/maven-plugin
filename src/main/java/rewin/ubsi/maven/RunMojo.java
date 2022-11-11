package rewin.ubsi.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import rewin.ubsi.common.Util;
import rewin.ubsi.consumer.Context;
import rewin.ubsi.container.Info;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.*;

/**
 * 启动容器并加载指定的微服务/过滤器：mvn ubsi:run -Dport={端口号} -Ddir={目标目录} -Dclass={微服务/过滤器的className}
 */
@Mojo(
        name = "run",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME
)
@Execute(phase = LifecyclePhase.PACKAGE)
public class RunMojo extends AbstractUbsiMojo {

    private final static String MODULE_FILE = "rewin.ubsi.module.json";     // 容器加载的模块
    private final static String MODULE_PATH = "rewin.ubsi.modules";         // 模块运行目录
    private final static String LIB_FILE = "rewin.ubsi.lib.json";           // JAR包之间的依赖关系
    private final static String LIB_PATH = "rewin.ubsi.libs";               // JAR包所在目录
    private final static String SYS_PATH = "core-libs";                     // 容器运行时的JAR包所在目录
    private final static String RUN_PATH = "maven-run";                     // 容器运行目录

    private final static String CONSUMER_FILE = "rewin.ubsi.consumer.json";
    private final static String ROUTER_FILE = "rewin.ubsi.router.json";
    private final static String LOG_FILE = "rewin.ubsi.log.json";
    private final static String CONTAINER_FILE = "rewin.ubsi.container.json";
    private final static String ACL_FILE = "rewin.ubsi.acl.json";

    private static class Module {
        public String   class_name;         // Java类名字
        public Info.GAV jar_lib;            // 依赖的JAR包
        public boolean  startup = false;    // 是否启动
    }

    private static class Modules {
        public  Map<String, Module> services;   // 服务
        public  List<Module>        filters;    // 过滤器
    }

    /* 容器监听端口，可以在配置文件rewin.ubsi.container.json中设置，也可以使用 -Dport={xxx} 来指定 */
    @Parameter( property = "port", defaultValue = "0")
    private int port = 0;       // 容器的监听端口

    /* 容器运行目录，可以使用 -Ddir={xxx} 来指定 */
    @Parameter( property = "dir", defaultValue = RUN_PATH)
    private String dir;         // 容器的运行目录

    private Map<String,String> configMap = new HashMap<>();     // 有配置参数的服务/过滤器
    private Process process;    // 容器的运行进程
    private int status = 0;     // 容器是否已经启动

    // 转换数据结构
    private Info.Lib artifact2Lib(Artifact artifact, File jar) throws MojoExecutionException {
        Info.Lib lib = new Info.Lib();
        lib.groupId = artifact.getGroupId();
        lib.artifactId = artifact.getArtifactId();
        lib.version = artifact.getVersion();
        if ( jar != null )
            lib.jarFile = jar.getName();
        return lib;
    }

    // 目录复制
    private void copyDir(File srcDir, File destDir) throws Exception {
        if ( !srcDir.exists() || !srcDir.isDirectory() )
            throw new Exception("resource_path \"" + srcDir + "\" not found.");

        if ( !destDir.exists() )
            destDir.mkdirs();
        for ( File file : srcDir.listFiles() ) {
            if ( file.isDirectory() )
                copyDir(file, new File(destDir, file.getName()));
            else if ( file.isFile() )
                Files.copy(file.toPath(), new File(destDir, file.getName()).toPath());
        }
    }

    // 处理模块
    private Module dealModule(Config.Service srv, Info.Lib lib, File modulePath) throws Exception {
        Module module = new Module();
        module.class_name = srv.className;
        module.jar_lib = new Info.GAV(lib.groupId, lib.artifactId, lib.version);
        String name = srv.name == null ? srv.className : srv.name;
        String config = Util.checkEmpty(srv.configJson);
        if ( config != null ) {
            module.startup = false;
            configMap.put(name, config);
        } else
            module.startup = true;
        if ( Util.checkEmpty(srv.resourcePath) != null )
            copyDir(new File(srv.resourcePath), new File(modulePath, name));    // 复制资源文件
        return module;
    }

    // 添加一个core依赖包，保留更高的version
    private void addCore(Set<Artifact> core, Artifact artifact) {
        Artifact jar = null;
        boolean is_ubsi_core = isUbsiCore(artifact.getArtifactId());
        for ( Artifact art : core ) {
            if ( is_ubsi_core && isUbsiCore(art.getArtifactId()) ) {
                jar = art;
                break;
            }
            if ( art.getGroupId().equals(artifact.getGroupId()) && art.getArtifactId().equals(artifact.getArtifactId()) ) {
                jar = art;
                break;
            }
        }
        if ( jar == null ) {
            core.add(artifact);
            return;
        }

        int compare = artifact.getVersion().compareTo(jar.getVersion());
        if ( is_ubsi_core ) {
            if ( compare < 0 )
                return;
            if ( compare == 0 ) {
                if ( artifact.getArtifactId().equals(jar.getArtifactId()) )
                    return;
                if ( jar.getArtifactId().contains("rewin") )
                    return;
            }
        } else if ( compare <= 0 )
            return;
        core.remove(jar);
        core.add(artifact);
    }

    // 处理JAR包及依赖关系
    private void dealDependency() throws MojoExecutionException {
        Set<Artifact> core = new HashSet<>();
        for ( Artifact artifact : project.getArtifacts() )
            if ( isSysLib(artifact.getGroupId(), artifact.getArtifactId()) )
                addCore(core, artifact);

        Set<Info.Lib> all = new HashSet<>();
        Set<File> jars = new HashSet<>();
        for ( Config.Service srv : services ) {
            Set<Artifact> deps = getDependency(srv);
            List<Info.Lib> libs = new ArrayList<>();
            // 处理依赖的JAR包
            for ( Artifact artDep : deps ) {
                if ( isSysLib(artDep.getGroupId(), artDep.getArtifactId()) ) {
                    addCore(core, artDep);
                    continue;
                }
                File jarDep = checkArtifact(artDep);
                Info.Lib libDep = artifact2Lib(artDep, jarDep);
                if ( !all.contains(libDep) ) {
                    all.add(libDep);
                    jars.add(jarDep);
                }
                libs.add(libDep);
            }
            // 处理服务的JAR包
            Artifact artSrv = getArtifact(srv);
            File jarSrv = checkArtifact(artSrv);
            Info.Lib libSrv = artifact2Lib(artSrv, jarSrv);
            if ( !libs.isEmpty() ) {
                int len = libs.size();
                libSrv.depends = new Info.GAV[len];
                for ( int i = 0; i < len; i ++ ) {
                    Info.Lib gav = libs.get(i);
                    libSrv.depends[i] = new Info.GAV(gav.groupId, gav.artifactId, gav.version);
                }
            }
            all.add(libSrv);
            jars.add(jarSrv);
        }

        // 保存JAR包依赖关系
        try {
            Util.saveJsonFile(new File(dir, LIB_FILE), all);
        } catch (Exception e) {
            throw new MojoExecutionException("save \"" + LIB_FILE + "\" error", e);
        }

        // 复制JAR包到LIB目录
        try {
            File libDir = new File(dir, LIB_PATH);
            Util.rmdir(libDir);
            libDir.mkdirs();
            for ( File f : jars )
                Files.copy(f.toPath(), new File(libDir, f.getName()).toPath());
            File sysDir = new File(dir, SYS_PATH);
            Util.rmdir(sysDir);
            sysDir.mkdirs();
            for ( Artifact artifact : core ) {
                File f = checkArtifact(artifact);
                Files.copy(f.toPath(), new File(sysDir, f.getName()).toPath());
            }
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("copy jar file error", e);
        }

        // 设置容器需要加载的模块
        try {
            File modulePath = new File(dir, MODULE_PATH);
            Util.rmdir(modulePath);

            Modules modules = new Modules();
            for ( Config.Service srv : services ) {
                if ( srv.name != null && modules.services == null )
                    modules.services = new HashMap<>();
                else if ( srv.name == null && modules.filters == null )
                    modules.filters = new ArrayList<>();
                Module module = dealModule(srv, artifact2Lib(getArtifact(srv), null), modulePath);
                if ( srv.name != null ) {
                    if ( modules.services == null )
                        modules.services = new HashMap<>();
                    modules.services.put(srv.name, module);
                } else {
                    if ( modules.filters == null )
                        modules.filters = new ArrayList<>();
                    modules.filters.add(module);
                }
            }
            Util.saveJsonFile(new File(dir, MODULE_FILE), modules);
        } catch (Exception e) {
            throw new MojoExecutionException("deal module error", e);
        }
    }

    // 处理配置文件
    private void dealConfigFile() throws MojoExecutionException {
        File[] cfgFiles = new File[] {
                new File(CONSUMER_FILE),
                new File(ROUTER_FILE),
                new File(LOG_FILE),
                new File(CONTAINER_FILE),
                new File(ACL_FILE)
        };
        for ( File f : cfgFiles ) {
            try {
                if (checkFile(f))
                    Files.copy(f.toPath(), new File(dir, f.getName()).toPath());
            } catch (Exception e) {
                throw new MojoExecutionException("copy \"" + f.getName() + "\" to \"" + dir + "\" error", e);
            }
        }
    }

    /** 模块运行 */
    public void execute() throws MojoExecutionException {
        prepare();

        dir = Util.checkEmpty(dir);
        if ( dir == null )
            dir = RUN_PATH;

        dealDependency();
        dealConfigFile();

        System.out.println("\n====== start ubsi-container, press CTRL-C to exit ======\n");

        try {
            ProcessBuilder builder;
            if ( port == 0 )
                builder = new ProcessBuilder("java", "-cp", SYS_PATH + File.separator + "*", "rewin.ubsi.container.Bootstrap");
            else
                builder = new ProcessBuilder("java", "-cp", SYS_PATH + File.separator + "*", "-Dubsi.port=" + port, "rewin.ubsi.container.Bootstrap");
            builder.redirectErrorStream(true);
            builder.directory(new File(dir));
            process = builder.start();
        } catch (Exception e) {
            throw new MojoExecutionException("start container error, " + e);
        }

        new Thread(() -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            try {
                String s;
                while ((s = reader.readLine()) != null) {
                    System.out.println(s);      // 截获container进程的输出
                    checkOutput(s);
                }
            } catch (IOException e) {
                System.out.println("====== container-output error, " + e + " ======");
            }
        }).start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {     // JVM退出时的Hook
            if ( process.isAlive() ) {
                process.destroyForcibly();
                while ( process.isAlive() )
                    try { Thread.sleep(100); } catch (Exception e) {}
            }
            if ( status == 0 )
                System.out.println("\n====== ubsi-container not start ======\n");
            else
                System.out.println("\n====== ubsi-container stopped ======\n");
        }));

        try { process.waitFor(); } catch (Exception e) {}
        throw new MojoExecutionException("ubsi-container start error!");
    }

    // 检查ubsi-contianer的日志输出，获得监听端口
    void checkOutput(String s) {
        if ( status > 0 )
            return;
        if ( !s.matches("^\\[INFO].*rewin\\.ubsi\\.container\\.Bootstrap#start\\(\\)#[0-9]*.*startup.*$") )
            return;

        status = 1;
        if ( configMap.isEmpty() )
            return;

        int index = s.indexOf("#");
        int tail = s.length();
        for ( int i = index + 1; i < tail; i ++ ) {
            char c = s.charAt(i);
            if ( c < '0' || c > '9' ) {
                tail = i;
                break;
            }
        }
        try {
            port = Integer.parseInt(s.substring(index + 1, tail));
        } catch (Exception e) {
            System.out.println("====== invalid container's port! ======");
            return;
        }

        System.out.println("====== ubsi-container started, waiting for " + configMap.keySet() + " to config ======");

        try {
            Context.startup(".");
            for ( String name : configMap.keySet() ) {
                Context.request("", "setConfig", name, configMap.get(name)).direct("localhost", port);
                Context.request("", "setStatus", name, 1).direct("localhost", port);
            }
        } catch (Exception e) {
            System.out.println("====== config error, " + e + " ======");
        }
        try { Context.shutdown(); } catch (Exception e) {}

        System.out.println("====== " + configMap.keySet() + " config and start over ======");
    }

}
