# maven-plugin
UBSI-Maven插件，可以部署或运行微服务。



在UBSI微服务的项目目录下，执行：

- mvn ubsi:run

  启动UBSI服务容器，并自动加载本项目指定的微服务/过滤器

  > 可以通过rewin.ubsi.container.json、rewin.ubsi.consumer.json、rewin.ubsi.router.json等配置文件来设置容器的运行参数

  

- mvn ubsi:deploy -Dcontainer={host#port}

  将本项目包含的微服务/过滤器部署到指定的UBSI服务容器中

  > 如果没有指定container参数，则默认"localhost#7112"



在项目的pom.xml中，需要添加ubsi-maven-plugin插件：

```xml
<build>
    <plugins>
        ......
        
        <plugin>
            <groupId>com.rewin</groupId>
            <artifactId>ubsi-maven-plugin</artifactId>
            <version>1.1.1</version>
            <configuration>     <!-- 插件的配置参数 -->
                <services>      <!-- 本项目包含的微服务 -->
                    <service>
                        <name>myservice.demo</name>	<!-- 服务名字 -->
                        <className>myservice.Demo</className>	<!-- 服务类名 -->
                        <configJson>    <!-- 微服务的配置参数，JSON格式的字符串 -->
                            <![CDATA[
                                "my service's configuration"
                            ]]>
                        </configJson>
                        <resourcePath>......</resourcePath>		<!-- 微服务的资源文件目录 -->
                        <exclusion>false</exclusion>            <!-- 是否排除部署/加载 -->
                    </service>
                    ......
                </services>
                <filters>       <!-- 本项目包含的过滤器 -->
                    <filter>
                        <className>myfilter.Demo</className>	<!-- 过滤器类名 -->
                        <configJson>    <!-- 过滤器的配置参数，JSON格式的字符串 -->
                            <![CDATA[
                                "my filter's configuration"
                            ]]>
                        </configJson>
                        <resourcePath>......</resourcePath>		<!-- 过滤器的资源文件目录 -->
                        <exclusion>true</exclusion>            <!-- 是否排除部署/加载 -->
                    </filter>
                    ......
                </filters>
            </configuration>
        </plugin>
    </plugins>
</build>        
```

