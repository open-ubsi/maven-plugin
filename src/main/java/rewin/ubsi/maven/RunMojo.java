package rewin.ubsi.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
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
 * 启动容器并加载指定的微服务/过滤器：mvn ubsi:run
 */
@Mojo(
        name = RunMojo.GOAL,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME
)
public class RunMojo extends AbstractUbsiMojo {

    final static String GOAL = "run";

    final static String MODULE_FILE = "rewin.ubsi.module.json";     // 容器加载的模块
    final static String MODULE_PATH = "rewin.ubsi.modules";         // 模块运行目录
    final static String LIB_FILE = "rewin.ubsi.lib.json";           // JAR包之间的依赖关系
    final static String LIB_PATH = "rewin.ubsi.libs";               // JAR包所在目录
    final static String SYS_PATH = "maven-run-libs";                // 容器运行时的JAR包所在目录

    static class Module {
        public String   class_name;         // Java类名字
        public Info.GAV jar_lib;            // 依赖的JAR包
        public boolean  startup = false;    // 是否启动
    }

    static class Modules {
        public  Map<String, Module> services;   // 服务
        public  List<Module>        filters;    // 过滤器
    }

    Info.Lib artifact2Lib(Artifact artifact, boolean file) throws MojoExecutionException {
        Info.Lib lib = new Info.Lib();
        lib.groupId = artifact.getGroupId();
        lib.artifactId = artifact.getArtifactId();
        lib.version = artifact.getVersion();
        if ( file )
            lib.jarFile = checkArtifact(artifact, GOAL).getName();
        return lib;
    }

    void copyDir(File srcDir, File destDir) throws Exception {
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

    Map<String,String> configMap = new HashMap<>();     // 有配置参数的服务/过滤器

    Module dealModule(Config.Filter filter, Info.Lib lib, String name, File modulePath) throws Exception {
        Module module = new Module();
        module.class_name = Util.checkEmpty(filter.className);
        if ( module.class_name == null )
            throw new Exception((name == null ? "filter" : name) + "'s <className> is empty in <configuration> of pom.xml.");
        module.jar_lib = new Info.GAV(lib.groupId, lib.artifactId, lib.version);
        String config = Util.checkEmpty(filter.configJson);
        if ( config != null ) {
            module.startup = false;
            configMap.put(name, config);
        } else
            module.startup = true;
        if ( Util.checkEmpty(filter.resourcePath) != null )
            copyDir(new File(filter.resourcePath), new File(modulePath, name));// 复制资源文件
        return module;
    }

    public void execute() throws MojoExecutionException {
        prepare(GOAL);

        // 处理依赖的JAR包
        List<Info.Lib> libs = new ArrayList<>();
        Set<Artifact> artifacts = project.getArtifacts();
        for ( Artifact artifact : artifacts ) {
            if ( isSysLib(artifact.getGroupId(), artifact.getArtifactId()) )
                continue;
            libs.add(artifact2Lib(artifact, true));
        }
        // 处理本项目的JAR包
        Info.Lib lib = artifact2Lib(project.getArtifact(), false);
        lib.jarFile = jarFile.getName();
        if ( !libs.isEmpty() ) {
            int len = libs.size();
            lib.depends = new Info.GAV[len];
            for ( int i = 0; i < len; i ++ ) {
                Info.Lib gav = libs.get(i);
                lib.depends[i] = new Info.GAV(gav.groupId, gav.artifactId, gav.version);
            }
        }
        libs.add(lib);
        // 保存JAR包依赖关系
        try {
            Util.saveJsonFile(new File(LIB_FILE), libs);
        } catch (Exception e) {
            throw new MojoExecutionException("save " + LIB_FILE + " error, " + e);
        }

        // 复制JAR包到LIB目录
        try {
            File libDir = new File(LIB_PATH);
            Util.rmdir(libDir);
            libDir.mkdir();
            File sysDir = new File(SYS_PATH);
            Util.rmdir(sysDir);
            sysDir.mkdir();
            for ( Artifact artifact : artifacts ) {
                File file = checkArtifact(artifact, GOAL);
                if ( isSysLib(artifact.getGroupId(), artifact.getArtifactId()) )
                    Files.copy(file.toPath(), new File(sysDir, file.getName()).toPath());
                else
                    Files.copy(file.toPath(), new File(libDir, file.getName()).toPath());
            }
            Files.copy(jarFile.toPath(), new File(libDir, jarFile.getName()).toPath());
        } catch (Exception e) {
            if ( e instanceof MojoExecutionException )
                throw (MojoExecutionException)e;
            throw new MojoExecutionException("copy jar file error, " + e);
        }

        // 设置容器需要加载的模块
        try {
            File modulePath = new File(MODULE_PATH);
            Util.rmdir(modulePath);

            Modules modules = new Modules();
            if ( !services.isEmpty() ) {
                modules.services = new HashMap<>();
                for ( Config.Service service : services ) {
                    String name = Util.checkEmpty(service.name);
                    if ( name == null )
                        throw new MojoExecutionException("service's name is empty in <configuration> of pom.xml.");
                    Module module = dealModule(service, lib, name, modulePath);
                    modules.services.put(name, module);
                }
            }
            if ( !filters.isEmpty() ) {
                modules.filters = new ArrayList<>();
                for ( Config.Filter filter : filters ) {
                    Module module = dealModule(filter, lib, Util.checkEmpty(filter.className), modulePath);
                    modules.filters.add(module);
                }
            }
            Util.saveJsonFile(new File(MODULE_FILE), modules);
        } catch (Exception e) {
            throw new MojoExecutionException("deal module error, " + e);
        }

        System.out.println("\n====== start ubsi-container, press CTRL-C to exit ======\n");

        try {
            ProcessBuilder builder = new ProcessBuilder("java", "-cp", SYS_PATH + File.separator + "*", "rewin.ubsi.container.Bootstrap");
            builder.redirectErrorStream(true);
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
                getLog().error("====== container-output error, " + e + " ======");
            }
        }).start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {     // JVM退出时的Hook
            if ( process.isAlive() )
                process.destroyForcibly();
            if ( status == 0 )
                System.out.println("\n====== ubsi-container not start ======\n");
            else
                System.out.println("\n====== ubsi-container stopped ======\n");
        }));

        try { process.waitFor(); } catch (Exception e) {}
        throw new MojoExecutionException("ubsi-container start error!");
    }

    Process process;    // 容器的运行进程
    int status = 0;     // 容器是否已经启动
    int port = 7112;    // 容器的监听端口

    void checkOutput(String s) {
        if ( status > 0 )
            return;
        if ( !s.matches("^\\[INFO\\].*rewin\\.ubsi\\.container\\.Bootstrap#start\\(\\)#[0-9]*.*startup.*$") )
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
            port = Integer.valueOf(s.substring(index + 1, tail));
        } catch (Exception e) {
            getLog().error("====== invalid container's port! ======");
            return;
        }

        try {
            Context.startup(".");
            for ( String name : configMap.keySet() ) {
                Context.request("", "setConfig", name, configMap.get(name)).direct("localhost", port);
                Context.request("", "setStatus", name, 1).direct("localhost", port);
            }
        } catch (Exception e) {
            getLog().error("====== set configuration error, " + e + " ======");
        }
        try { Context.shutdown(); } catch (Exception e) {}
    }

}
