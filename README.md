### 1 环境参数

IP 地址：10.109.246.71

- 物理机 105 上

数据库：10.109.246.143

### 2 工程配置

工程所需配置在 `resources` 目录下：

- conference.properties：会议相关配置
- env.properties：环境相关配置，目前只配置了 IP 地址（key 为 `realm`）
- jdbc.properties：数据库相关配置

### 3 环境搭建

#### 3.1 Linux

操作系统为：CentOS-7-x86_64-Minimal-1611

#### 3.2 配置 IP 地址

```bash
vim /etc/sysconfig/network-scripts/ifcfg-ens3
```

```bash
BOOTPROTO=static
ONBOOT=yes

IPADDR=10.109.246.71
NETMASK=255.255.255.0
GATEWAY=10.109.246.1
DNS1=10.3.9.4
```

#### 3.3 关闭防火墙

```bash
systemctl stop firewalld.service
systemctl disable firewalld.service
```

#### 3.4 基本组件

```bash
yum -y install lrzsz vim unzip net-tools gcc kernel-devel make ncurses-devel
```

#### 3.5 安装 Tmux （可选）

拷贝 `libevent-2.0.22-stable.tar.gz` 和 `tmux-2.1.tar.gz` 到 `/opt` 目录下

```bash
tar -xvzf libevent-2.0.22-stable.tar.gz && cd libevent-2.0.22-stable
./configure --prefix=/usr/local
make && make install
```

```bash
cd ..
tar -xvzf tmux-2.1.tar.gz && cd tmux-2.1
LDFLAGS="-L/usr/local/lib -Wl,-rpath=/usr/local/lib" ./configure --prefix=/usr/local
make && make install
```

#### 3.6 安装 JDK7

```bash
rpm -ivh jdk-7u79-linux-x64.rpm
java -version
```

#### 3.7 安装 JBoss

将 `mss-3.1.633-jboss-as-7.2.0.Final.zip` 置于 `/root` 下

```bash
unzip mss-3.1.633-jboss-as-7.2.0.Final.zip
```

将 `change-ip-sip-servlet.sh` 置于 `mss-3.1.633-jboss-as-7.2.0.Final` 下，执行前赋予权限

```bash
./change-ip-sip-servlet
```

#### 3.8 修改 dar 文件

```bash
vim /root/mss-3.1.633-jboss-as-7.2.0.Final/standalone/configuration/dars/mobicents-dar.properties
```

```properties
OPTION: ("SipApp", "DAR:From", "ORIGINATING", "", "NO_ROUTE", "0")
REGISTER: ("SipApp", "DAR:From", "ORIGINATING", "", "NO_ROUTE", "0")
INVITE: ("SipApp", "DAR:From", "ORIGINATING", "", "NO_ROUTE", "0")
ALL: ("SipApp","DAR:From", "ORIGINATING", "", "NO_ROUTE", "0")
```

### 4 部署并启动

#### 4.1 启动 Media Server

```bash
cd /root/mss-3.1.633-jboss-as-7.2.0.Final/mobicents-media-server/mms-server/bin
chmod +x run.sh
./run.sh
```

#### 4.2 启动 Sip Servlet

maven 打包

```bash
mvn clean package
```

部署 war 包（在开发机上）

```sh
scp ~/Documents/Eclipse/RestcommParent/sip-servlets-examples/SipApp/target/SipApp.war root@10.109.246.71:/root/mss-3.1.633-jboss-as-7.2.0.Final/standalone/deployments/
```

启动

```bash
cd /root/mss-3.1.633-jboss-as-7.2.0.Final/bin
./standlone.sh
```

>  附 change-ip-sip-servlet
>
>  ```bash
>  echo "enter ip :"
>  read ip
>
>  cd standalone/configuration/
>  sed -i "s/127.0.0.1/$ip/g" standalone-sip.xml standalone.xml
>  cd ../../
>
>  cd mobicents-media-server/mms-server/deploy/
>  sed -i "s/127.0.0.1/$ip/g" server-beans.xml
>  ```

>  附 x-Lite 的 SIP 账户配置
>
>  ![](https://ws1.sinaimg.cn/large/006tNc79ly1fliihsfthxj30i60h1wfy.jpg)


