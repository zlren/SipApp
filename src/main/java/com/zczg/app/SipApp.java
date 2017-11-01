package com.zczg.app;

import com.zczg.util.*;
import org.apache.log4j.Logger;
import org.mobicents.javax.media.mscontrol.spi.DriverImpl;

import javax.media.mscontrol.*;
import javax.media.mscontrol.join.Joinable.Direction;
import javax.media.mscontrol.mediagroup.MediaGroup;
import javax.media.mscontrol.mediagroup.Player;
import javax.media.mscontrol.mediagroup.PlayerEvent;
import javax.media.mscontrol.mixer.MediaMixer;
import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.media.mscontrol.networkconnection.SdpPortManager;
import javax.media.mscontrol.networkconnection.SdpPortManagerEvent;
import javax.media.mscontrol.resource.RTC;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.sip.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;


public class SipApp extends SipServlet implements TimerListener {

    private static final long serialVersionUID = 1L;

    private static Logger logger = Logger.getLogger(SipApp.class);
    private CurEnv cur_env = new CurEnv();

    private Map<String, String> authMap;
    private Map<String, SipUser> users; // 这里的users和sipUser的users应该是一回事

    private SipFactory sipFactory;
    private TimerService timerService;

    private static final String CONTACT_HEADER = "Contact";
    private static final String SUPPORT_HEADER = "Supported";

    private MediaSession mediaSession;
    private MsControlFactory msControlFactory;

    private Properties profMain = null;
    private static String LOCAL_ADDRESS = null;
    private static String CA_PORT = null;
    private static String PEER_ADDRESS = null;
    private static String MGW_PORT = null;

    public SipApp() {
        logger.info("New SipApp Instance");

        authMap = new HashMap<String, String>();
        users = new HashMap<String, SipUser>();
    }

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {

        super.init(servletConfig);
        logger.info("the SipApp has been started");

        sipFactory = (SipFactory) getServletContext().getAttribute(SIP_FACTORY);
        timerService = (TimerService) getServletContext().getAttribute(TIMER_SERVICE);

        Properties conferenceProperties = new Properties();
        try {
            InputStream resourceAsStream = JDBCUtils.class.getClassLoader().getResourceAsStream("conference.properties");
            conferenceProperties.load(resourceAsStream);
        } catch (IOException e) {
            e.printStackTrace();
        }

        String serverAddress = MediaConf.serverAddr;
        LOCAL_ADDRESS = serverAddress;
        PEER_ADDRESS = serverAddress;

        CA_PORT = "2722";
        MGW_PORT = "2427";

        profMain = new Properties();

        profMain.setProperty(MediaConf.MGCP_STACK_NAME, "SipApp");
        profMain.setProperty(MediaConf.MGCP_PEER_IP, PEER_ADDRESS);
        profMain.setProperty(MediaConf.MGCP_PEER_PORT, MGW_PORT);

        profMain.setProperty(MediaConf.MGCP_STACK_IP, LOCAL_ADDRESS);
        profMain.setProperty(MediaConf.MGCP_STACK_PORT, CA_PORT);

        try {
            msControlFactory = new DriverImpl().getFactory(profMain);
            mediaSession = msControlFactory.createMediaSession();
            logger.info("started MGCP Stack on " + LOCAL_ADDRESS + " and port " + CA_PORT);

            // FIXME 初始化ConfData
            ConfData.init(conferenceProperties);

        } catch (Exception e) {
            logger.error("couldn't start the underlying MGCP Stack", e);
        }

        Map<String, Object> map = JDBCUtils.queryForMap("select * from p2puser");
        logger.info("共有 " + map.size() + " 个用户");
        logger.info("ip地址为: " + MediaConf.serverAddr);

        // new Shower(users).start();
    }

    @Override
    protected void doRegister(SipServletRequest req) throws ServletException, IOException {

//		logger.info("Received register request: " + req.getTo());
//		logger.info(req.toString());

        String from = req.getFrom().getURI().toString();
        String contact = req.getHeader("Contact");
        String[] ss = contact.split("[@:;]");
        String username = ss[1];
        String ip = ss[2];
        String port = ss[3];
        logger.info("Register User " + username + " ip " + ip + ":" + port);

        String auth = req.getHeader("Proxy-Authorization");
        if (auth == null) {
            SipServletResponse resp = req.createResponse(SipServletResponse.SC_UNAUTHORIZED);

            String nonce = authMap.get(username);
            if (nonce == null) {
                nonce = RandomCharUtil.getRandomNumberUpperLetterChar(32);
                authMap.put(username, nonce);
            }

            resp.addHeader("Proxy-Authenticate", "Digest realm=\"" + cur_env.getSettings().get("realm") + "\""
                    + ",nonce=\"" + nonce + "\",algorithm=MD5");

            resp.send();
            logger.info("Request authenticate for " + from);
        } else {
            Map<String, Object> map = JDBCUtils.queryForMap("select * from p2puser where name = '" + username + "'");

            int st = auth.indexOf("response=\"") + 10; // ?
            int ed = auth.indexOf("\"", st);
            String digest = auth.substring(st, ed);
            st = auth.indexOf("uri=\"") + 5;
            ed = auth.indexOf("\"", st);
            String uri = auth.substring(st, ed);
            String method = req.getMethod();

            String check = cur_env.myDigest(username, cur_env.getSettings().get("realm"), (String) map.get("passwd"),
                    authMap.get(username), method, uri);

            if (digest.equals(check)) {
                SipServletResponse resp = req.createResponse(SipServletResponse.SC_OK);
                Address address = req.getAddressHeader(CONTACT_HEADER);
                resp.setAddressHeader(CONTACT_HEADER, address);

                int expires = address.getExpires();
                if (expires < 0) {
                    expires = req.getExpires();
                }

                if (expires == 0) {
                    users.remove(username);
                    logger.info("User " + from + " unregistered");
                } else {
                    // 成功注册（登录）的用户记录在users这个map中
                    users.put(username,
                            // cur_env.getSettings().get("realm") 服务器ip地址
                            new SipUser(username, "sip:" + username + '@' + cur_env.getSettings().get("realm"), ip,
                                    port, (Integer) map.get("priority"), (Boolean) map.get("wait"),
                                    (String) map.get("preforward_always"), (String) map.get("preforward_busy"),
                                    (String) map.get("preforward_timeout"), users, address));
                    logger.info("User " + from + " registered ");
                }

                resp.send();
            } else {
                SipServletResponse resp = req.createResponse(SipServletResponse.SC_FORBIDDEN);
                logger.info("User " + from + " registered fail");
                resp.send();
            }
        }
    }

    @Override
    protected void doInvite(SipServletRequest request) throws ServletException, IOException {

        SipSession session = request.getSession();
        setLock(session);

        try {
            logger.info("Got INVITE: " + request.toString());

            String fromName = ((request.getFrom().getURI().toString()).split("[:@]"))[1];
            String toName = ((request.getTo().getURI().toString()).split("[:@]"))[1];

            SipUser fromUser = users.get(fromName);
            SipUser toUser = null;

            if (fromUser == null) {
                request.createResponse(SipServletResponse.SC_UNAUTHORIZED).send();
                logger.info("User " + fromName + " is not registered");
                return;
            }

            // 拨打自己，禁止
            if (fromName.equals(toName)) {
                request.createResponse(SipServletResponse.SC_UNAUTHORIZED).send();
                logger.info("不能拨打自己");
                return;
            }

            // 想创建预约会议，但是创建者并不在此预约会议的成员列表中
            if (ConfData.isPreConf(toName) && users.get(toName) == null
                    && !ConfData.preConfMap.get(toName).contains(fromName)) {
                request.createResponse(SipServletResponse.SC_FORBIDDEN).send();
                logger.info("创建者" + fromName + "并不在预编程会议" + toName + "的成员列表中");

                return;
            }

            // 到这之后，fromUser一定是在线状态
            session.setAttribute("CUR_INVITE", request);
            session.setAttribute("NOT_FOUND", request.createResponse(SipServletResponse.SC_NOT_FOUND));

            // 考虑前转和忙转，确定toName
            if (fromUser.realUser.containsKey(toName)) {

                logger.info("Exist call from " + fromName + " to " + toName);

                // 从fromUser的realUser中取出toName对应的真实toName
                toName = fromUser.realUser.get(toName);
                toUser = users.get(toName);

            } else {
                logger.info("New call from " + fromName + " to " + toName);

                // 判断被叫是不是自己
                if (fromName.equals(toName) || fromUser.sessions.containsKey(toName)) {
                    request.createResponse(SipServletResponse.SC_FORBIDDEN).send();
                    logger.info("User " + toName + " is trying to call himself or an existed call");
                    return;
                }

                // 备份的名字
                String initName = toName;

                if (ConfData.isConf(toName)) {
                    if (users.get(toName) == null) {
                        // 可以的
                    } else {
                        // 加入会议，不允许
                        request.createResponse(SipServletResponse.SC_FORBIDDEN).send();
                        logger.info(fromName + "禁止加入会议：" + toName);
                        return;
                    }

                } else {

                    // 处理无条件前转，只传递一次
                    toUser = users.get(toName);

                    // toName不在线（没登录，所以没在users中）
                    if (toUser == null) {
                        // 这个时候要考虑是不是设置了无条件前转，设置了无条件前转的用户在不在线都要转给设置的用户
                        // 由于不在线，toName的相关数据从数据库中去找
                        Map<String, Object> userto = JDBCUtils
                                .queryForMap("select * from user where name = '" + toName + "'");

                        // 没上线的这个用户在数据库中存在，且设置了无条件前转，将转给的这个人赋值给toName，toName不为null表示被叫设置了这个无条件前转
                        // 这个时候toName是转给的sipUser的name
                        if (userto != null && (toName = (String) userto.get("preforward_always")) != null) {
                            // 回主叫181，表示正在转接
                            request.createResponse(SipServletResponse.SC_CALL_BEING_FORWARDED).send();
                            logger.info("Always preforword to " + toName);
                        } else {
                            // 数据库中不存在被叫sip用户或者没上线也没设置无条件前转，那就是找不到被叫了..直接404 not
                            // found，结束通话
                            request.createResponse(SipServletResponse.SC_NOT_FOUND).send();
                            logger.info("Not online " + initName); // 不在线或不存在的用户，log可以改一下
                            return;
                        }

                        // toName在线，那这个时候toName的具体信息就可以在users中查出来，就是toUser的内容
                        // 判断是不是设置了无条件前转，如果是则回181给主叫，并且这时候toName设置成了无条件前转的用户
                        // 如果没有设置的话，toName就成了null
                    } else if ((toName = toUser.preforwardAlways) != null) {
                        request.createResponse(SipServletResponse.SC_CALL_BEING_FORWARDED).send();
                        logger.info("Always preforword to " + toName);
                    }

                    // toName为null表示toName在线，且没有设置无条件前转
                    // toName还应该是原来的备份名字
                    if (toName == null)
                        toName = initName; // 被赋值初始备份的名字

                    // session从invite中提取的session，也就是caller的session
                    session.setAttribute("USER", fromName);
                    session.setAttribute("OPPO", toName); // toName已经考虑了无条件前转的情况

                    // 拿到toName对应的详细sipUser
                    toUser = users.get(toName);

                    // toUser不在线，被叫设置的无条件前转的用户不在线
                    if (toUser == null) {
                        request.createResponse(SipServletResponse.SC_NOT_FOUND).send();
                        logger.info("User " + toName + " is not online");
                        return;
                    }

                    // 上面已经处理完了无条件前转（优先考虑）的情况，到这里被叫对象一定在线
                    // 接下来处理忙转

                    // 判断toUser是不是处于忙的状态
                    // 如果toUser忙
                    if (toUser.isBusy()) {
                        // 且主叫优先级高于被叫的通话对象
                        // 强拆强插

                        String theUserNameWhoIsCallingWithTheCallee = "";
                        for (Map.Entry<String, SipSession> entry : toUser.sessions.entrySet()) {
                            if (entry.getValue().equals(SipUser.CALLING)) {
                                theUserNameWhoIsCallingWithTheCallee = entry.getKey();
                            }
                        }


                        if (fromUser.compareTo(users.get(theUserNameWhoIsCallingWithTheCallee)) < 0) {
                            // // 强拆强插逻辑
                            // fromUser.sessions.put(toName, session);
                            // fromUser.setState(toName, SipUser.IDLE);
                            //
                            // fromUser.setState(toName,
                            // SipUser.PRIORITY_CHECK);
                            // // 到这里主叫确定了被叫对象，向主叫回复响铃
                            // request.createResponse(SipServletResponse.SC_RINGING).send();
                            // // 主叫和ms建立连接
                            // createConnWithMS(session);
                            // //
                            // 到这里返回，因为这里进入到强插强插逻辑，说明被叫和被叫的通话对象都是已经接入到sip服务器的，也就是和ms有了conn
                            // return;

                            // 对方忙，但是对方没有设置呼叫等待，考虑忙转
                            // 如果对方设置了呼叫等待呢？##############################################
                        } else if (!toUser.wait) {
                            // 处理遇忙转接，只传递一次（A呼叫B，B设置了忙转给C，如果C再忙就呼叫失败，回主叫BUSY）
                            // 忙转，toName设置成忙转的对象
                            toName = toUser.preforwardBusy;

                            // 设置了忙转，toName是忙转的对象
                            if (toName != null) {
                                // 应该判断是否为null吧###
                                toUser = users.get(toName);

                                if (toUser == null) {
                                    request.createResponse(SipServletResponse.SC_NOT_FOUND).send();
                                    logger.info("User " + toName + " is not online");
                                    return;
                                } else {
                                    if (toUser.isBusy()) {
                                        request.createResponse(SipServletResponse.SC_BUSY_HERE).send();
                                        logger.info("User " + toName + " is busy");
                                        return;
                                    } else {
                                        request.createResponse(SipServletResponse.SC_CALL_BEING_FORWARDED).send();
                                        session.setAttribute("OPPO", toName);
                                        logger.info("Busy preforward to " + toName);
                                    }
                                }

                                // // 忙转的对象还tm忙，那此次呼叫失败
                                // // 进入这个if说明toUser不为null也就是忙转对象在线，并且忙转对象处于忙的状态
                                // if (toUser.isBusy()) {
                                //     request.createResponse(SipServletResponse.SC_BUSY_HERE).send();
                                //     logger.info("User " + toName + " is busy");
                                //     return;
                                // } else {
                                //     // 进入这个else有两种情况
                                //     // 1> toUser为null表示忙转对象不在线，应该回not found
                                //     // 2> toUser不为null表示在线，并且不忙
                                //     // 只有第2中情况才表示忙转对象可用，打给忙转对象
                                //     //
                                //     // #######################################################
                                //     request.createResponse(SipServletResponse.SC_CALL_BEING_FORWARDED).send();
                                //     session.setAttribute("OPPO", toName);
                                //     logger.info("Busy preforward to " + toName);
                                // }

                                // 对方忙，没有设置呼叫等待，并且没有设置忙转
                                // 那就是呼叫失败，但是这里我觉得应该返回busy吧..###################################
                            } else {
                                request.createResponse(SipServletResponse.SC_BUSY_HERE).send();
                                logger.info("User " + toName + " is not online");
                                return;
                            }
                        }
                    }

                }
                // fromUser存储自己负责和toName交流的session
                fromUser.sessions.put(toName, session);
                // fromUser的状态设置为null
                fromUser.setState(toName, SipUser.IDLE);

                // a申请和b通话，b经过一系列复杂的判断（无条件前转、忙转等等）后确定了被叫是c，也许c就是b，这时候需要在a的realUser中记录下b到c的映射
                // 对于fromuser来说，起初是initName，最后是toName
                fromUser.realUser.put(initName, toName);

                if (!ConfData.isConf(toName)) {
                    // 对于toUser来说，fromName就是fromName
                    toUser.realUser.put(fromName, fromName);
                }

                // toUser.sessions.put(fromName, value)
            }

            // fromUser为idle，发出的invite是new-invite
            if (fromUser.compareState(toName, SipUser.IDLE)) {

                if (ConfData.isConf(toName)) {
                    logger.info("创建会议！！" + fromName + " 创建 " + toName);

                    SipConf sipConf = new SipConf(toName, mediaSession.createMediaMixer(MediaMixer.AUDIO), fromName,
                            "sip:" + toName + '@' + cur_env.getSettings().get("realm"));
                    sipConf.priority = 0; // 会议的优先级比较高，一般不被强拆强插
                    users.put(toName, sipConf);

                    session.setAttribute("MEDIA_SESSION", mediaSession);
                    session.setAttribute("USER", fromName);
                    session.setAttribute("OPPO", toName);
                    fromUser.sessions.put(toName, session);

                    fromUser.setState(toName, SipUser.WAITING_FOR_MEDIA_SERVER);
                    createConnWithMS(session);

                } else {
                    logger.info("This is an invite from " + fromName + " to " + toName);

                    request.createResponse(SipServletResponse.SC_RINGING).send();

                    Address from = sipFactory.createAddress(fromUser.sipadd + ":5080");
                    Address to = sipFactory.createAddress("sip:" + toUser.name + "@" + toUser.ip + ":" + toUser.port);
                    SipServletRequest invite = sipFactory.createRequest(sipFactory.createApplicationSession(), "INVITE",
                            from, to);

                    if (request.getHeader(SUPPORT_HEADER) != null) {
                        invite.setHeader(SUPPORT_HEADER, request.getHeader(SUPPORT_HEADER));
                    }

                    SipSession linkedSession = invite.getSession();
                    toUser.sessions.put(fromName, linkedSession);

                    fromUser.setState(toName, SipUser.WAITING_FOR_MEDIA_SERVER);
                    toUser.setState(fromName, SipUser.INIT_BRIDGE);

                    session.setAttribute("MEDIA_SESSION", mediaSession);
                    // session.setAttribute("CUR_INVITE", request); 前面已经放过了

                    linkedSession.setAttribute("MEDIA_SESSION", mediaSession);
                    linkedSession.setAttribute("CUR_INVITE", invite);
                    linkedSession.setAttribute("USER", toName);
                    linkedSession.setAttribute("OPPO", fromName);

                    // linkedSession使用和session一样的锁
                    shareLock(session, linkedSession);

                    // 主叫和ms建立连接
                    createConnWithMS(session);
                }

            } else {
                // re-invite

                SipUser stateUser = null;

                if (fromUser.linkUser.get(toName) != null) // 意味着什么？？
                    stateUser = fromUser;
                else if (!(ConfData.isConf(toName)) && (toUser.linkUser.get(fromName)) != null)
                    stateUser = toUser;

                if (stateUser != null) {
                    toName = (String) session.getAttribute("OPPO");
                    toUser = users.get(toName);
                }

                // re-invite怎么理解？这个re-invite是在什么情况下谁给谁发出的？
                // A正在通话状态为CALLING，A按下保持键（暂停键）想保持这一路通话，发送了re-invite消息
                if (fromUser.compareState(toName, SipUser.CALLING)) {

                    logger.info("这是一个用来呼叫保持的re-invite");

                    if (ConfData.isConf(toName)) {

                        // 保持一个会议
                        fromUser.setState(toName, SipUser.HOLDON_HOST);
                        SipConf sipConf = (SipConf) users.get(toName);
                        sipConf.memberMap.put(fromName, SipConf.HOLD_CONF); // 从in-conf改为hold-conf

                        NetworkConnection conn = (NetworkConnection) session.getAttribute("NETWORK_CONNECTION");
                        conn.unjoin(sipConf.mixer);

                        mediaSession.removeAttribute("SIP_SESSION" + conn.getURI());
                        conn.release();
                        session.removeAttribute("NETWORK_CONNECTION");

                        createConnWithMS(session);

                    } else {

                        // FIXME 这句话会报空指针异常
                        SipSession linkedSession = toUser.sessions.get(fromName);
                        NetworkConnection conn1 = (NetworkConnection) session.getAttribute("NETWORK_CONNECTION");
                        NetworkConnection conn2 = (NetworkConnection) linkedSession.getAttribute("NETWORK_CONNECTION");

                        fromUser.setState(toName, SipUser.HOLDON_HOST);
                        toUser.setState(fromName, SipUser.HOLDON_GUEST);

                        MediaMixer initMixer = (MediaMixer) session.getAttribute("MIXER");
                        conn1.unjoin(initMixer);
                        conn2.unjoin(initMixer);
                        // session的mixer接下里会怎么处理？释放掉吗？##############################################

                        // 被动保持者播放提示音
                        runDialog(linkedSession, MediaConf.WELCOME_MSG);

                        // 主动保持者的conn先释放再重新申请
                        // 为什么re-invite需要释放掉之前的连接再重新申请呢？re-invite消息中携带了新的sdp，所以需要双方重新协商
                        mediaSession.removeAttribute("SIP_SESSION" + conn1.getURI());
                        conn1.release();
                        session.removeAttribute("NETWORK_CONNECTION");

                        // 重新申请连接，申请好以后，判断session的状态为hold-host，发送ok消息
                        createConnWithMS(session);
                    }


                } else if (fromUser.compareState(toName, SipUser.HOLDON_HOST)) {
                    // fromUser的状态为holdon-host，fromUser是主动保持者，发送re-invite想要释放这个保持

                    if (ConfData.isConf(toName)) {
                        // 释放保持，恢复和会议的连接
                        logger.info("恢复和会议的连接");

                        NetworkConnection conn = (NetworkConnection) session.getAttribute("NETWORK_CONNECTION");

                        mediaSession.removeAttribute("SIP_SESSION" + conn.getURI());
                        conn.release();
                        session.removeAttribute("NETWORK_CONNECTION");

                        SipConf sipConf = (SipConf) users.get(toName);
                        sipConf.memberMap.put(fromName, SipConf.IN_CONF); // 从hold-conf改为in-conf，方便ms判断

                        conn = createConnWithMS(session);
                        sipConf.joinConf(fromName, conn);

                    } else {
                        logger.info("This is a re-invite for release hold on");

                        SipSession linkedSession = toUser.sessions.get(fromName);
                        NetworkConnection conn1 = (NetworkConnection) session.getAttribute("NETWORK_CONNECTION");
                        NetworkConnection conn2 = (NetworkConnection) linkedSession.getAttribute("NETWORK_CONNECTION");

                        mediaSession.removeAttribute("SIP_SESSION" + conn1.getURI());
                        conn1.release();
                        session.removeAttribute("NETWORK_CONNECTION");

                        // 如果toUser的状态为HOLDON_GUEST
                        // 那么可以重新通话
                        if (toUser.compareState(fromName, SipUser.HOLDON_GUEST)) {
                            toUser.setState(fromName, SipUser.CALLING);
                            releaseDialog(linkedSession, conn2);

                            MediaMixer initMixer = (MediaMixer) session.getAttribute("MIXER");
                            conn1 = createConnWithMS(session);
                            conn1.join(Direction.DUPLEX, initMixer);
                            conn2.join(Direction.DUPLEX, initMixer);
                        } else if (toUser.compareState(fromName, SipUser.HOLDON_HOST)) {
                            fromUser.setState(toName, SipUser.HOLDON_GUEST);
                            createConnWithMS(session);
                            runDialog(session, MediaConf.WELCOME_MSG);
                        }
                    }

                } else if (fromUser.compareState(toName, SipUser.HOLDON_GUEST)) {
                    logger.info("被保持者发送re-invite发起保持");
                    logger.info("This is a re-invite for guest to hold on");
                    NetworkConnection conn = (NetworkConnection) session.getAttribute("NETWORK_CONNECTION");

                    fromUser.setState(toName, SipUser.HOLDON_HOST);
                    releaseDialog(session, conn);

                    mediaSession.removeAttribute("SIP_SESSION" + conn.getURI());
                    conn.release();
                    session.removeAttribute("NETWORK_CONNECTION");
                    createConnWithMS(session);

                } else {
                    request.createResponse(SipServletResponse.SC_SERVER_INTERNAL_ERROR).send();
                    logger.info("Not defined operation");
                }

                // 来自非中心节点
                // if (stateUser != null &&
                //         !fromName.equals(stateUser.name)) {
                //     SipUser rdUser = stateUser.getAvaliable(fromName);
                //
                //     if (rdUser == null) {
                //         // 不应该发生
                //         logger.error("Undefined situation");
                //     } else {
                //         String rdName = rdUser.name;
                //         SipSession toSession = getLinkedSession(fromName,
                //                 toName);
                //         SipSession rdSession = getLinkedSession(toName, rdName);
                //         toSession.setAttribute("OPPO", rdName);
                //
                //         NetworkConnection toConn = (NetworkConnection)
                //                 toSession.getAttribute("NETWORK_CONNECTION");
                //         NetworkConnection rdConn = (NetworkConnection)
                //                 rdSession.getAttribute("NETWORK_CONNECTION");
                //         MediaMixer mixer = (MediaMixer)
                //                 toSession.getAttribute("MIXER");
                //
                //         if (toUser.compareState(rdName, SipUser.HOLDON_HOST)) {
                //             releaseDialog(rdSession, rdConn);
                //             releaseDialog(toSession, toConn);
                //             rdUser.setState(toName, SipUser.CALLING);
                //             toUser.setState(rdName, SipUser.CALLING);
                //
                //             toConn.join(Direction.DUPLEX, mixer);
                //             rdConn.join(Direction.DUPLEX, mixer);
                //         } else if (toUser.compareState(rdName,
                //                 SipUser.HOLDON_GUEST)) {
                //
                //         } else {
                //             logger.error("Undefined situation");
                //         }
                //     }
                // }

            }
            // }
        } catch (Exception e) {
            request.createResponse(SipServletResponse.SC_SERVER_INTERNAL_ERROR).send();
            e.printStackTrace();
        } finally {
            setUnLock(session);
        }
    }

    @Override
    protected void doInfo(SipServletRequest request) throws ServletException, IOException {

        SipSession session = request.getSession();
        setLock(session);

        try {
            // sending response
            // 收到info消息，回200 ok
            SipServletResponse response = request.createResponse(SipServletResponse.SC_OK);
            response.send();

            // 如果是a呼叫b，b无条件前转给c，那a发info的时候实际上info消息的to字段应该还是b
            // 但是a对应的session的OPPO记录了真实的另一方
            // SipUser的oppo和session的OPPO属性有什么区别？###############################################
            String fromName = (String) session.getAttribute("USER");
            SipUser fromUser = users.get(fromName);
            String toName = (String) session.getAttribute("OPPO");
            SipUser toUser = users.get(toName);

            // Getting the message content
            // 得到消息内容
            String messageContent = new String((byte[]) request.getContent());
            logger.info("got INFO request with following content " + messageContent);
            int signalIndex = messageContent.indexOf("Signal=");

            // deal with DTMF
            if (messageContent != null && messageContent.length() > 0 && signalIndex != -1) {
                String signal = messageContent.substring("Signal=".length()).trim();
                // signal是具体内容，只有1位
                signal = signal.substring(0, 1);
                logger.info("哈哈哈 Signal received " + signal);

                // alice把bob踢掉
                if (signal.equals("9")) {
                    if (ConfData.isConf(toName)) {

                        SipUser byeUser = users.get("bob");
                        SipSession byeSession = byeUser.sessions.get(toName);
                        SipConf sipConf = (SipConf) users.get(toName);

                        sipConf.exitConf("bob", (NetworkConnection) byeSession.getAttribute("NETWORK_CONNECTION"));

                        SipServletRequest bye = byeSession.createRequest("BYE");
                        byeUser.setState(toName, SipUser.END);

                        bye.send();
                    }
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            setUnLock(session);
        }
    }

    // 转移
    @Override
    protected void doRefer(SipServletRequest request) throws ServletException, IOException {

    }

    @Override
    protected void doSuccessResponse(SipServletResponse resp) throws ServletException, IOException {
        SipSession session = resp.getSession();
        setLock(session);
        try {
            logger.info("Got OK");

            String fromName = (String) session.getAttribute("USER");
            SipUser fromUser = users.get(fromName);
            String toName = (String) session.getAttribute("OPPO");
            SipUser toUser = users.get(toName);

            if (fromUser == null) {
                logger.info("Got OK for MESSAGE !");
            } else {
                if (session.isValid()) {

                    ServletTimer st = (ServletTimer) session.getAttribute("TIMER");
                    if (st != null)
                        st.cancel();

                    String cSeqValue = resp.getHeader("CSeq");
                    if (cSeqValue.indexOf("INVITE") != -1) {
                        if (fromUser.compareState(toName, SipUser.INIT_BRIDGE)) { // 这里是不是有问题

                            // 得到被叫回复的ok消息的sdp
                            byte[] sdpAnswer = resp.getRawContent();
                            if (sdpAnswer == null) {
                                sdpAnswer = (byte[]) session.getAttribute("180SDP");
                                if (sdpAnswer == null) {
                                    // internal error
                                }
                            }

                            // 从被叫回复的ok消息中取出sip终端的sdp存起来
                            // session.setAttribute("SDP", sdpOffer);

                            // 邀请被叫，并且被叫回复了200 ok，这时候制造ack消息存起来，先不回给被叫
                            // 拿着被叫回给的200 ok的session去和ms申请conn
                            SipServletRequest ack = resp.createAck();
                            session.setAttribute("PREPARE_ACK", ack);

                            fromUser.setState(toName, SipUser.ANSWER_BRIDGE);

                            answerSDP(session, sdpAnswer);
                        }
                    } else if (fromUser.compareState(toName, SipUser.END)) {

                        // server主动给user发bye消息结束通话的情况下，user回的ok消息
                        logger.info("OK for BYE/CANCEL/...");
                        releaseSession(resp.getSession());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            setUnLock(session);
        }
    }

    @Override
    protected void doErrorResponse(SipServletResponse resp) throws ServletException, IOException {
        SipSession session = resp.getSession();
        setLock(session);
        try {
            logger.info("Got ERROR: \n" + resp.toString());

            String fromName = (String) session.getAttribute("USER");
            SipUser fromUser = users.get(fromName);
            String toName = (String) session.getAttribute("OPPO");
            SipUser toUser = users.get(toName);

            // ACK is auto send by the container
            if (session != null && session.isValid()) {
                ServletTimer st = (ServletTimer) session.getAttribute("TIMER");
                if (st != null) {
                    st.cancel();
                }

                SipSession linkedSession = getLinkedSession(fromName, toName);
                if (linkedSession != null && linkedSession.isValid()) {
                    SipServletRequest request = (SipServletRequest) linkedSession.getAttribute("CUR_INVITE");
                    SipServletResponse re = request.createResponse(resp.getStatus());
                    toUser.setState(fromName, SipUser.END);
                    re.send();
                    logger.info(re.toString());
                    releaseSession(linkedSession);
                }

                fromUser.setState(toName, SipUser.END);
                releaseSession(session);
            }
        } finally {
            setUnLock(session);
        }
    }

    @Override
    protected void doAck(SipServletRequest request) throws ServletException, IOException {
        SipSession session = request.getSession();
        setLock(session);

        try {
            logger.info("Got ACK: \n" + request.toString());

            String fromName = (String) session.getAttribute("USER");
            SipUser fromUser = users.get(fromName);
            String toName = (String) session.getAttribute("OPPO");
            SipUser toUser = users.get(toName);

            // if (session.isValid())
            if (session.isValid()) {
                // 这个条件可以满足吗
                if (fromUser.compareState(toName, SipUser.WAITING_FOR_ACK)
                        && toUser.compareState(fromName, SipUser.WAITING_FOR_ACK)) {
                    fromUser.setState(toName, SipUser.CALLING);
                    toUser.setState(fromName, SipUser.CALLING);
                } else if (fromUser.compareState(toName, SipUser.END)) {
                    logger.info("ACK for error...");
                    releaseSession(session);

                    // 会议
                } else if (fromUser.compareState(toName, SipUser.WAITING_FOR_BRIDGE) && ConfData.isConf(toName)) {
                    SipConf sipConf = (SipConf) users.get(toName);
                    sipConf.joinConf(fromName, (NetworkConnection) session.getAttribute("NETWORK_CONNECTION"));
                    fromUser.setState(toName, SipUser.CALLING);

                    // 主持人创建并加入会议完成之后，如果是预编程会议需要邀请与会者
                    if (fromName.equals(sipConf.host)) {
                        if (ConfData.isPreConf(sipConf.confKey)) {
                            Set<String> memberSet = ConfData.preConfMap.get(sipConf.confKey);
                            for (String member : memberSet) {
                                if (users.containsKey(member)) {
                                    if (!member.equals(sipConf.host)) {
                                        SipUser memberUser = users.get(member);
                                        try {
                                            Address from = sipFactory.createAddress(sipConf.sipadd + ":5080");
                                            Address to = sipFactory.createAddress(
                                                    "sip:" + member + "@" + memberUser.ip + ":" + memberUser.port);
                                            SipServletRequest invite = sipFactory.createRequest(
                                                    sipFactory.createApplicationSession(), "INVITE", from, to);

                                            SipServletRequest hostInvite = (SipServletRequest) session
                                                    .getAttribute("CUR_INVITE");
                                            if (hostInvite.getHeader(SUPPORT_HEADER) != null) {
                                                invite.setHeader(SUPPORT_HEADER, hostInvite.getHeader(SUPPORT_HEADER));
                                            }

                                            SipSession memberSession = invite.getSession();

                                            memberUser.sessions.put(sipConf.confKey, memberSession);
                                            memberUser.setState(sipConf.confKey, SipUser.INIT_BRIDGE);

                                            memberSession.setAttribute("CUR_INVITE", invite);
                                            memberSession.setAttribute("USER", member);
                                            memberSession.setAttribute("OPPO", sipConf.confKey);

                                            invite.send();

                                        } catch (ServletParseException ignored) {

                                        }
                                    }

                                } else {
                                    logger.info(member + "不在线！");
                                }

                            }

                        }
                    }
                }
            }
        } finally {
            setUnLock(session);
        }
    }

    @Override
    protected void doCancel(SipServletRequest request) throws ServletException, IOException {

        SipSession session = request.getSession();
        setLock(session);

        try {
            logger.info("Got CANCEL: \n" + request.toString());

            String fromName = (String) session.getAttribute("USER");
            SipUser fromUser = users.get(fromName);
            String toName = (String) session.getAttribute("OPPO");
            SipUser toUser = users.get(toName);

            fromUser.setState(toName, SipUser.END);
            SipServletResponse ok = request.createResponse(SipServletResponse.SC_OK);
            ok.send();

            SipSession linkedSession = getLinkedSession(fromName, toName);
            if (linkedSession != null && linkedSession.isValid()) {
                SipServletRequest originInvite = (SipServletRequest) linkedSession.getAttribute("CUR_INVITE");
                SipServletRequest cancel = originInvite.createCancel();
                toUser.setState(fromName, SipUser.END);
                cancel.send();
                logger.info(cancel.toString());
            }

            releaseSession(session);
        } finally {
            setUnLock(session);
        }
    }

    @Override
    protected void doBye(SipServletRequest request) throws ServletException, IOException {

        SipSession session = request.getSession();
        setLock(session);

        try {
            logger.info("Got BYE: \n" + request.toString());

            String fromName = (String) session.getAttribute("USER");
            SipUser fromUser = users.get(fromName);
            String toName = (String) session.getAttribute("OPPO");
            SipUser toUser = users.get(toName);

            fromUser.setState(toName, SipUser.END);

            // 对bye消息回ok
            SipServletResponse sipServletResponse = request.createResponse(SipServletResponse.SC_OK);
            sipServletResponse.send();

            // 先这么处理吧..
            if (ConfData.isConf(toName)) {
                SipConf sipConf = (SipConf) users.get(toName);
                sipConf.exitConf(fromName, (NetworkConnection) session.getAttribute("NETWORK_CONNECTION"));
            } else {
                SipUser stateUser = null;
                if (fromUser.linkUser.get(toName) != null)
                    stateUser = fromUser;
                else if (toUser.linkUser.get(fromName) != null)
                    stateUser = toUser;

                if (stateUser == null) {
                    SipSession linkedSession = getLinkedSession(fromName, toName);
                    if (linkedSession != null && linkedSession.isValid()) {
                        SipServletRequest bye = linkedSession.createRequest("BYE");
                        toUser.setState(fromName, SipUser.END);
                        bye.send();
                        logger.info("Bye to " + toName);
                    }
                } else {
                }
            }

            releaseSession(session);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            setUnLock(session);
        }
    }

    @Override
    protected void doMessage(SipServletRequest request) throws ServletException, IOException {

    }

    /**
     * 实现了TimerListener接口，timeout是接口中的方法
     * <p>
     * 超时前转以及其余超时情况处理
     */
    public void timeout(ServletTimer st) {

        logger.info("进入超时逻辑");

        // 定时器置于被叫的session中
        final SipServletRequest calleeRequest = (SipServletRequest) st.getInfo();

        // 超时转时的session是被叫的session
        // 所以从request里面取出的session的变量名叫做linkedSession
        SipSession calleeSession = calleeRequest.getSession();
        setLock(calleeSession);

        try {
            // 被叫的信息 这里从to字段取值
            String callerName = ((calleeRequest.getFrom().getURI().toString()).split("[:@]"))[1];
            SipUser callerUser = users.get(callerName);
            String calleeName = ((calleeRequest.getTo().getURI().toString()).split("[:@]"))[1];
            SipUser calleeUser = users.get(calleeName);

            logger.info("主叫是: " + callerName + ", 被叫是：" + calleeName);

            // 被叫的状态是INIT_BRIDGE，说明是主叫呼叫被叫，被叫长时间未接听
            if (calleeUser.compareState(callerName, SipUser.INIT_BRIDGE)) {

                logger.info("被叫: " + calleeName + "长时间未接，考虑超时转接");

                String forwardName = calleeUser.preforwardTimeout;
                SipUser forwardUser = users.get(forwardName);

                logger.info("超时转接对象是：" + forwardName);

                SipSession callerSession = getLinkedSession(calleeName, callerName);
                callerUser.setState(calleeName, SipUser.END);

                SipServletRequest calleeInvite = (SipServletRequest) calleeSession.getAttribute("CUR_INVITE");
                calleeInvite.createCancel().send();

                calleeUser.clean(callerName);

                SipServletRequest callerInvite = (SipServletRequest) callerSession.getAttribute("CUR_INVITE");

                // 如果转接被叫不在线
                if (forwardUser == null) {
                    callerInvite.createResponse(SipServletResponse.SC_NOT_FOUND).send();
                    logger.info("超时转接对象不在线");
                } else if (forwardUser.isBusy()) {
                    callerInvite.createResponse(SipServletResponse.SC_BUSY_HERE).send();
                    logger.info("超时转接对象忙！！");
                } else {
                    Address to = sipFactory.createAddress("sip:" + forwardName + "@" + forwardUser.ip + ":" +
                            forwardUser.port);
                    Address from = sipFactory.createAddress(callerUser.sipadd + ":5080");

                    SipServletRequest inviteForForwardUser = sipFactory
                            .createRequest(calleeSession.getApplicationSession(), "INVITE", from, to);

                    if (calleeRequest.getHeader(SUPPORT_HEADER) != null) {
                        inviteForForwardUser.setHeader(SUPPORT_HEADER, calleeRequest.getHeader(SUPPORT_HEADER));
                    }

                    SipSession forwardUserSession = inviteForForwardUser.getSession();
                    shareLock(callerSession, forwardUserSession);

                    forwardUserSession.setAttribute("CUR_INVITE", inviteForForwardUser);
                    forwardUserSession.setAttribute("MEDIA_SESSION", mediaSession);
                    forwardUserSession.setAttribute("USER", forwardName);
                    forwardUserSession.setAttribute("OPPO", callerName);
                    callerSession.setAttribute("OPPO", forwardName);

                    forwardUser.sessions.put(callerName, forwardUserSession);
                    callerUser.clean(calleeName);
                    callerUser.sessions.put(forwardName, callerSession);
                    forwardUser.setState(callerName, SipUser.INIT_BRIDGE);
                    callerUser.setState(forwardName, SipUser.WAITING_FOR_BRIDGE);

                    generateSDP(forwardUserSession);

                    logger.info("转接到" + forwardName);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            setUnLock(calleeSession);
        }
    }

    public NetworkConnection createConnWithMS(SipSession session) throws MsControlException, IOException {

        SipServletRequest invite = (SipServletRequest) session.getAttribute("CUR_INVITE");

        NetworkConnection conn = mediaSession.createNetworkConnection(NetworkConnection.BASIC);
        SdpPortManager sdpManag = conn.getSdpPortManager();

        MyRtpPortsListener networkConnectionListener = new MyRtpPortsListener();
        sdpManag.addListener(networkConnectionListener);

        session.setAttribute("NETWORK_CONNECTION", conn);
        mediaSession.setAttribute("SIP_SESSION" + conn.getURI(), session);

        byte[] sdpOffer = invite.getRawContent();
        if (sdpOffer == null) {
            sdpOffer = (byte[]) session.getAttribute("SDP");
            if (sdpOffer == null) {
                logger.error("NO SDP");
            }
        }

        conn.getSdpPortManager().processSdpOffer(sdpOffer);

        return conn;
    }

    private class MyRtpPortsListener implements MediaEventListener<SdpPortManagerEvent> {
        public void onEvent(SdpPortManagerEvent event) {

            logger.info("Got EVENT " + event.getSource().getContainer().getURI());
            logger.info(event.toString());

            MediaSession mediaSession = event.getSource().getMediaSession();

            SipSession sipSession = (SipSession) mediaSession
                    .getAttribute("SIP_SESSION" + event.getSource().getContainer().getURI());

            setLock(sipSession);

            try {
                String fromName = (String) sipSession.getAttribute("USER");
                String toName = (String) sipSession.getAttribute("OPPO");
                SipUser fromUser = users.get(fromName);
                SipUser toUser = users.get(toName);

                SipSession linkedSession = getLinkedSession(fromName, toName);

                SipServletRequest inv = (SipServletRequest) sipSession.getAttribute("CUR_INVITE");

                if (linkedSession != null)
                    logger.info(sipSession.getAttribute("STATE") + " : " + linkedSession.getAttribute("STATE"));

                try {
                    if (event.isSuccessful()) {
                        if (fromUser.compareState(toName, SipUser.WAITING_FOR_MEDIA_SERVER)) {
                            if (linkedSession != null) {
                                if (toUser.compareState(fromName, SipUser.INIT_BRIDGE)) {
                                    // 主叫发出invite邀请，媒体服务器处理完主叫invite中的sdp的时候

                                    // 这个invite是在处理主叫的invite的时候，制作的向被叫发送的invite
                                    SipServletRequest inviteForCallee = (SipServletRequest) linkedSession
                                            .getAttribute("CUR_INVITE");
                                    if (inviteForCallee != null) {

                                        SipServletResponse responseOkForCaller = inv
                                                .createResponse(SipServletResponse.SC_OK);
                                        byte[] sdp = event.getMediaServerSdp(); // 媒体服务器的SDP
                                        responseOkForCaller.setContent(sdp, "application/sdp");
                                        responseOkForCaller.getSession().setAttribute("PREPARE_OK",
                                                responseOkForCaller);
                                        fromUser.setState(toName, SipUser.WAITING_FOR_BRIDGE);

                                        // 这句话向被叫发出的invite邀请是没有sdp的
                                        // inviteForCallee.setContent(sdp, "application/sdp");
                                        // inviteForCallee.send();

                                        generateSDP(linkedSession);

                                        logger.info("Start Timer");

                                        ServletTimer st = timerService.createTimer(
                                                inviteForCallee.getApplicationSession(), 10000, false,
                                                (Serializable) inviteForCallee);

                                        // 定时器放在了linkedSession中
                                        linkedSession.setAttribute("TIMER", st);
                                    } else {
                                        // INTERNAL ERROR
                                    }

                                }
                            } else {
                                // linkSession == null， 会议
                                // 创建会议
                                SipConf sipConf = (SipConf) users.get(toName);
                                if (sipConf.memberMap.size() == 0) {
                                    SipServletResponse okForConfHost = inv.createResponse(SipServletResponse.SC_OK);
                                    byte[] sdp = event.getMediaServerSdp();
                                    okForConfHost.setContent(sdp, "application/sdp");

                                    fromUser.setState(toName, SipUser.WAITING_FOR_BRIDGE);
                                    okForConfHost.send();
                                } else {

                                    sipConf.joinConf(fromName,
                                            (NetworkConnection) sipSession.getAttribute("NETWORK_CONNECTION"));

                                    fromUser.setState(toName, SipUser.CALLING);

                                    // 被邀请加入会议成功后，realUser要放入，这样被邀请的成员以后发的用户保持和恢复的invite就会被看成existing-call
                                    fromUser.realUser.put(toName, toName);

                                    // 被邀请加入会议
                                    SipServletRequest ack = (SipServletRequest) sipSession.getAttribute("PREPARE_ACK");
                                    byte[] sdp = event.getMediaServerSdp();
                                    ack.setContent(sdp, "application/sdp");
                                    ack.send();
                                }
                            }
                        } else if (fromUser.compareState(toName, SipUser.INIT_BRIDGE)) {
                            // 改动之后，向被叫发invite的时候带上了媒体服务器的sdp，被叫的状态是INIT_BRIDGE
                            byte[] sdp = event.getMediaServerSdp();
                            inv.setContent(sdp, "application/sdp");
                            inv.send();

                        } else if (fromUser.compareState(toName, SipUser.ANSWER_BRIDGE)) { //

                            NetworkConnection conn1 = (NetworkConnection) sipSession.getAttribute("NETWORK_CONNECTION");
                            NetworkConnection conn2 = (NetworkConnection) linkedSession.getAttribute
                                    ("NETWORK_CONNECTION");

                            MediaMixer myMixer = mediaSession.createMediaMixer(MediaMixer.AUDIO);
                            conn1.join(Direction.DUPLEX, myMixer);
                            conn2.join(Direction.DUPLEX, myMixer);

                            sipSession.setAttribute("MIXER", myMixer);
                            linkedSession.setAttribute("MIXER", myMixer);

                            fromUser.setState(toName, SipUser.WAITING_FOR_ACK);
                            toUser.setState(fromName, SipUser.WAITING_FOR_ACK);

                            SipServletResponse resp2 = (SipServletResponse) linkedSession.getAttribute("PREPARE_OK");
                            resp2.send();

                            SipServletRequest ack = (SipServletRequest) sipSession.getAttribute("PREPARE_ACK");
                            byte[] sdp = event.getMediaServerSdp();
                            ack.setContent(sdp, "application/sdp");
                            ack.send();

                        } else if (fromUser.compareState(toName, SipUser.HOLDON_HOST)) {
                            if (ConfData.isConf(toName)) {
                                // 那我如何判断与会者是想保持还是想恢复呢
                                // 双人通话可以通过这一路的对方的状态来判断
                                // 所以可以把conf里面的memberList改为Map，value为与会者的状态
                                SipConf sipConf = (SipConf) users.get(toName);
                                if (sipConf.memberMap.get(fromName).equals(SipConf.HOLD_CONF)) {
                                    // 保持
                                    SipServletResponse resp = inv.createResponse(SipServletResponse.SC_OK);
                                    byte[] sdp = event.getMediaServerSdp();
                                    resp.setContent(sdp, "application/sdp");
                                    resp.send();
                                } else {
                                    // 恢复
                                    SipServletResponse resp = inv.createResponse(SipServletResponse.SC_OK);
                                    byte[] sdp = event.getMediaServerSdp();
                                    resp.setContent(sdp, "application/sdp");

                                    fromUser.setState(toName, SipUser.CALLING);

                                    resp.send();
                                }

                            } else {
                                // 主动保持通话者
                                logger.info("用于呼叫保持的一方发送的re-invite收到媒体服务器回应");

                                SipServletResponse resp = inv.createResponse(SipServletResponse.SC_OK);
                                byte[] sdp = event.getMediaServerSdp();
                                resp.setContent(sdp, "application/sdp");

                                // FIXME
                                // 这是什么情况##################################################################
                                // A和B正在通话，A保持，这时候A重新申请conn前A的状态设置为了holdon-host
                                // A想和B恢复通话，A恢复的时候，如果B是holdon-guest，那就可以直接恢复
                                // 申请conn前B的状态改为了calling，所以A看到B的状态为calling，自己的状态也是calling才对
                                if (!ConfData.isConf(toName)) {
                                    if (toUser.compareState(fromName, SipUser.CALLING)) {
                                        fromUser.setState(toName, SipUser.CALLING);
                                    }
                                }

                                resp.send();

                                logger.info("send resp for holdon invite");
                            }
                        } else if (fromUser.compareState(toName, SipUser.HOLDON_GUEST)) {
                            // 被保持者也主动发起保持
                            logger.info("被保持者也主动发起保持申请");
                            logger.info("receive response from MG for holdon change to hold on guest");
                            SipServletResponse resp = inv.createResponse(SipServletResponse.SC_OK);
                            byte[] sdp = event.getMediaServerSdp();
                            resp.setContent(sdp, "application/sdp");
                            resp.send();
                            logger.info("send resp for holdon change invite");
                        } else if (fromUser.compareState(toName, SipUser.WAITING_FOR_BRIDGE)) {

                        } else if (fromUser.compareState(toName, SipUser.PRIORITY_CHECK)) {

                        }
                    } else {
                        if (SdpPortManagerEvent.SDP_NOT_ACCEPTABLE.equals(event.getError())) {
                            // Send 488 error response to INVITE
                            inv.createResponse(SipServletResponse.SC_NOT_ACCEPTABLE_HERE).send();
                        } else if (SdpPortManagerEvent.RESOURCE_UNAVAILABLE.equals(event.getError())) {
                            // Send 486 error response to INVITE
                            inv.createResponse(SipServletResponse.SC_BUSY_HERE).send();
                        } else {
                            // Some unknown error. Send 500 error response to
                            // INVITE
                            inv.createResponse(SipServletResponse.SC_SERVER_INTERNAL_ERROR).send();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } finally {
                setUnLock(sipSession);
            }
        }
    }

    /**
     * 邀请被叫，邀请的时候带上媒体服务器的SDP
     *
     * @param session
     * @throws MsControlException
     * @throws IOException
     */
    private void generateSDP(SipSession session) throws MsControlException, IOException {

        NetworkConnection conn = mediaSession.createNetworkConnection(NetworkConnection.BASIC);
        SdpPortManager sdpManag = conn.getSdpPortManager();

        MyRtpPortsListener networkConnectionListener = new MyRtpPortsListener();
        sdpManag.addListener(networkConnectionListener);

        session.setAttribute("NETWORK_CONNECTION", conn);
        mediaSession.setAttribute("SIP_SESSION" + conn.getURI(), session);

        sdpManag.generateSdpOffer();
    }

    /**
     * 处理被叫回的200-ok中带的sdp
     *
     * @param session
     * @param sdpAnswer
     * @throws MsControlException
     * @throws IOException
     */
    private void answerSDP(SipSession session, byte[] sdpAnswer) throws MsControlException, IOException {

        NetworkConnection conn = (NetworkConnection) session.getAttribute("NETWORK_CONNECTION");
        SdpPortManager sdpManag = conn.getSdpPortManager();

        MyRtpPortsListener networkConnectionListener = new MyRtpPortsListener();
        sdpManag.addListener(networkConnectionListener);

        sdpManag.processSdpAnswer(sdpAnswer);
    }

    private void setLock(SipSession session) {
        if (session.isValid()) {
            ReentrantLock r = (ReentrantLock) session.getAttribute("LOCK");
            if (r == null) {
                r = new ReentrantLock();
                session.setAttribute("LOCK", r);
            }
            logger.info("SET LOCK " + r.toString());
            r.lock();
        }
    }

    private void setUnLock(SipSession session) {
        if (session.isValid()) {
            ReentrantLock r = (ReentrantLock) session.getAttribute("LOCK");
            if (r != null && r.isLocked()) {
                logger.info("SET UNLOCK " + r.toString());
                r.unlock();
            }
        }
    }

    private void shareLock(SipSession src, SipSession dest) {
        setLock(dest);
        ReentrantLock r0 = (ReentrantLock) dest.getAttribute("LOCK");
        ReentrantLock r = (ReentrantLock) src.getAttribute("LOCK");
        if (r != null) {
            dest.setAttribute("LOCK", r);
        } else {
            r = new ReentrantLock();
            src.setAttribute("LOCK", r);
            dest.setAttribute("LOCK", r);
        }
        r0.unlock();
    }

    private SipSession getLinkedSession(String fromName, String toName) {
        try {
            return users.get(toName).sessions.get(fromName);
        } catch (Exception e) {
            logger.warn("No user");
            return null;
        }
    }

    private void releaseSession(SipSession session) {
        if (session != null && session.isValid()) {
            destroyLock(session);

            NetworkConnection conn = (NetworkConnection) session.getAttribute("NETWORK_CONNECTION");
            if (conn != null) {
                releaseDialog(session, conn);
                conn.release();
            }

            String fromName = (String) session.getAttribute("USER");
            SipUser from = users.get(fromName);
            SipUser to = users.get((String) session.getAttribute("OPPO"));
            from.clean(to.name);

            // 修改适配conf
            if (to != null && !ConfData.isConf(to.name)) {
                to.clean(from.name);
            } else if (to != null && ConfData.isConf(to.name)) {
                SipConf sipConf = (SipConf) users.get(to.name);

                if (sipConf.memberMap.size() == 0) {
                    users.remove(sipConf.confKey);
                    logger.info("销毁会议");
                } else if (fromName.equals(sipConf.host)) { // sipConf.memberMap.size()
                    // > 0 &&
                    // 主持人退出会议，解散会议
                    logger.info("主持人退出会议");
                    Set<String> memberSet = sipConf.memberMap.keySet();

                    for (String member : memberSet) {
                        logger.info(member + "被迫退出");
                        SipUser byeUser = users.get(member);
                        SipSession byeSession = byeUser.sessions.get(sipConf.confKey);
                        sipConf.exitConf(member, (NetworkConnection) byeSession.getAttribute("NETWORK_CONNECTION"));
                        SipServletRequest bye = byeSession.createRequest("BYE");
                        byeUser.setState(sipConf.confKey, SipUser.END);
                        try {
                            bye.send();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }

            session.invalidate();
        }
    }

    private void destroyLock(SipSession session) {
        if (session.isValid()) {
            ReentrantLock r = (ReentrantLock) session.getAttribute("LOCK");
            if (r != null && r.isLocked()) {
                logger.info("SET UNLOCK " + r.toString());
                r.unlock();
            }

            session.removeAttribute("LOCK");
        }
    }

    protected void releaseDialog(SipSession sipSession, NetworkConnection conn) {
        MediaGroup mg = (MediaGroup) sipSession.getAttribute("MEDIAGROUP");

        if (mg != null) {
            if (conn != null)
                try {
                    mg.unjoin(conn);
                } catch (MsControlException e) {
                    e.printStackTrace();
                }

            mg.release();
            sipSession.removeAttribute("MEDIAGROUP");
        }
    }

    class MyPlayerListener implements MediaEventListener<PlayerEvent> {

        public void onEvent(PlayerEvent event) {
            log("Play terminated with: " + event);
            // Release the call and terminate
            MediaSession mediaSession = event.getSource().getMediaSession();
            SipSession sipSession = (SipSession) mediaSession
                    .getAttribute("SIP_SESSION" + event.getSource().getContainer().getURI());
            terminate(sipSession, mediaSession);
        }
    }

    protected void runDialog(SipSession sipSession, String src) {
        URI prompt = URI.create(src);
        try {
            MediaGroup mg;
            mg = (MediaGroup) sipSession.getAttribute("MEDIAGROUP");
            if (mg == null) {
                mg = mediaSession.createMediaGroup(MediaGroup.PLAYER);
                sipSession.setAttribute("MEDIAGROUP", mg);
                MyPlayerListener playerListener = new MyPlayerListener();
                mg.getPlayer().addListener(playerListener);
                mg.join(Direction.DUPLEX, (NetworkConnection) sipSession.getAttribute("NETWORK_CONNECTION"));
            }
            // Play prompt
            Parameters playParams = msControlFactory.createParameters();
            playParams.put(Player.REPEAT_COUNT, 10000);
            playParams.put(Player.INTERVAL, 2);
            mg.getPlayer().play(prompt, RTC.NO_RTC, playParams);
            logger.info("Play " + prompt);
        } catch (Exception e) {
            // internal error
            e.printStackTrace();
        }
    }

    protected void terminate(SipSession sipSession, MediaSession mediaSession) {
        setLock(sipSession);
        try {
            SipUser user = users.get(sipSession.getAttribute("USER"));
            user.setState((String) sipSession.getAttribute("OPPO"), SipUser.END);
            sipSession.createRequest("BYE").send();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            setUnLock(sipSession);
        }
    }
}
