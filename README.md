#### 1 环境参数

IP 地址：10.109.246.71

数据库：10.109.246.143

#### 2 环境搭建

##### 2.1 Linux

操作系统为 centos-1611-minimal (7.3)

##### 2.2 配置 ip 地址

```shell
vim /etc/sysconfig/network-scripts/ifcfg-ens3
```

```shell
BOOTPROTO=static
ONBOOT=yes

IPADDR=10.109.246.71
NETMASK=255.255.255.0
GATEWAY=10.109.246.1
DNS1=10.3.9.4
```

##### 2.3 关闭防火墙

```shell
systemctl stop firewalld.service
systemctl disable firewalld.service
```

##### 2.4 基本组件

```shell
yum -y install lrzsz vim unzip net-tools gcc kernel-devel make ncurses-devel
```

##### 2.5 安装 tmux

拷贝 `libevent-2.0.22-stable.tar.gz` 和 `tmux-2.1.tar.gz` 到 `/opt` 目录下

```shell
tar -xvzf libevent-2.0.22-stable.tar.gz && cd libevent-2.0.22-stable
./configure --prefix=/usr/local
make && make install
```

```shell
cd ..
tar -xvzf tmux-2.1.tar.gz && cd tmux-2.1
LDFLAGS="-L/usr/local/lib -Wl,-rpath=/usr/local/lib" ./configure --prefix=/usr/local
make && make install
```

##### 2.6 安装 JDK7

```shell
rpm -ivh jdk-7u79-linux-x64.rpm
java -version
```

##### 2.7 安装 jboss

将 `mss-3.1.633-jboss-as-7.2.0.Final.zip` 置于 `/root` 下

```shell
unzip mss-3.1.633-jboss-as-7.2.0.Final.zip
```

将 `change-ip-sip-servlet.sh` 置于 `mss-3.1.633-jboss-as-7.2.0.Final` 下，执行前赋予权限

```shell
./change-ip-sip-servlet
```

启动 `media-server`

```shell
cd /root/mss-3.1.633-jboss-as-7.2.0.Final/mobicents-media-server/mms-server/bin
chmod 777 run.sh
./run.sh
```

修改 `dar` 文件

```shell
vim /root/mss-3.1.633-jboss-as-7.2.0.Final/standalone/configuration/dars/mobicents-dar.properties
```

```shell
OPTION: ("SipApp", "DAR:From", "ORIGINATING", "", "NO_ROUTE", "0")
REGISTER: ("SipApp", "DAR:From", "ORIGINATING", "", "NO_ROUTE", "0")
INVITE: ("SipApp", "DAR:From", "ORIGINATING", "", "NO_ROUTE", "0")
ALL: ("SipApp","DAR:From", "ORIGINATING", "", "NO_ROUTE", "0")
```

##### 2.8 配置数据库

新建 `env` 目录

```shell
cd /root/mss-3.1.633-jboss-as-7.2.0.Final
mkdir env && cd env
vim jdbc.properties
```

```properties
jdbc.driver=com.mysql.jdbc.Driver
jdbc.url=jdbc:mysql://10.109.246.143:3306/wuhan_neiwaitong
jdbc.username=zlren
jdbc.password=Lab2016!
```

```shell
echo "realm 10.109.246.71" > sysstr.env
```

#### 3 部署并启动

部署 war 包（在开发机上）

```shell
scp ~/Documents/Eclipse/RestcommParent/sip-servlets-examples/SipApp/target/SipApp.war root@10.109.246.71:/root/mss-3.1.633-jboss-as-7.2.0.Final/standalone/deployments/
```

启动

```sh
cd /root/mss-3.1.633-jboss-as-7.2.0.Final/bin
./run.sh
```







