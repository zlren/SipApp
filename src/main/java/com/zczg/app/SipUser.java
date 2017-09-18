package com.zczg.app;

import org.apache.log4j.Logger;

import javax.servlet.sip.Address;
import javax.servlet.sip.SipSession;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

public class SipUser implements Comparable<SipUser> {
    // Each incoming call goes through the following states:
    private static Logger logger = Logger.getLogger(SipUser.class);

    public final static String IDLE = "IDLE";
    public final static String INIT_BRIDGE = "INIT_BRIDGE";
    public final static String WAITING_FOR_MEDIA_SERVER = "WAITING_FOR_MEDIA_SERVER";
    public final static String WAITING_FOR_ACK = "WAITING_FOR_ACK";
    public final static String WAITING_FOR_BRIDGE = "WAITING_FOR_BRIDGE";
    public final static String CALLING = "CALLING";
    public final static String HOLDON_HOST = "HOLDON_HOST";
    public final static String HOLDON_GUEST = "HOLDON_GUEST";
    public final static String ONCALL_INVITE = "ONCALL_INVITE";
    public final static String PRIORITY_CHECK = "PRIORITY_CHECK";
    public final static String END = "END";
    public final static String ANSWER_BRIDGE = "ANSWER_BRIDGE";

    public Integer priority;
    private ReentrantLock r;
    public String name;
    public String sipadd; // sip地址 sip:username@服务器ip
    public String ip; // 此用户真实ip地址
    public String port; // 端口号
    public String preforwardAlways; // 无条件前转
    public String preforwardBusy; // 遇忙前转
    public String preforwardTimeout; // 超时前转
    public Address contact;

    public Boolean wait; // 是否设置了呼叫等待
    private Boolean busy; // 是否忙

    public Map<String, PriorityQueue<SipUser>> linkUser;
    public Map<String, String> realUser; // doInvite、doInfo、doRefer、timeOut
    public Map<String, SipSession> sessions;
    public Map<String, SipUser> users;
    public String oppo; // doInfo、强拆强插

    public SipUser() {
    }

    public SipUser(String _name, String _sipadd, String _ip, String _port, Integer _priority, Boolean _wait,
                   String _preforwardAlways, String _preforwardBusy, String _preforwardTimeout, Map<String, SipUser>
                           _users, Address _contact) {
        name = _name;
        sipadd = _sipadd;
        ip = _ip;
        port = _port;
        priority = _priority;
        wait = _wait;
        preforwardAlways = _preforwardAlways;
        preforwardBusy = _preforwardBusy;
        preforwardTimeout = _preforwardTimeout;
        contact = _contact;

        busy = false;
        oppo = null;
        r = new ReentrantLock();
        linkUser = new HashMap<String, PriorityQueue<SipUser>>();
        realUser = new HashMap<String, String>();
        sessions = new HashMap<String, SipSession>();
        users = _users;
    }

    public SipUser(String _name, String _sipadd, String _ip, String _port, Integer _priority) {
        name = _name;
        sipadd = _sipadd;
        ip = _ip;
        port = _port;
        priority = _priority;
        wait = false;
        preforwardAlways = null;
        preforwardBusy = null;
        preforwardTimeout = null;

        busy = false;
        oppo = null;
        r = new ReentrantLock();
        linkUser = new HashMap<String, PriorityQueue<SipUser>>();
        realUser = new HashMap<String, String>();
        sessions = new HashMap<String, SipSession>();
        users = null;
    }

    // 这个地方应该是user1和user2去比，user1.compareTo(user2)，如果这个值小于0表示user1的优先级更高
    // linkUser的value是优先级队列，在队列中插入元素<SipUser>的时候队列本身会根据元素的优先级在队列中排序，因此SipUser需要实现Comparable接口
    // comparaTo就是在比较两个user的优先级的时候自动调用
    @Override
    public int compareTo(SipUser other) {

        // -1为更优先
        if (other == null)
            return -1;

        // 不懂 自己的sessions存储了和自己有关系的b以及和b有关系的自己的session
        // sessions.get(name)有值吗？
        boolean w1 = HOLDON_GUEST.equals(sessions.get(name).getAttribute("STATE")); // ################################
        boolean w2 = HOLDON_GUEST.equals(other.sessions.get(name).getAttribute("STATE"));

        if (w1 && !w2)
            return -1;
        else if (w2 && !w1)
            return 1;

        int dis = 0;
        dis = this.priority - other.priority;

        if (dis > 0)
            return 1;
        else if (dis == 0)
            return 0;
        else
            return -1;
    }

    public boolean compareState(String name, String state) {
        r.lock();
        try {
            return state.equals(sessions.get(name).getAttribute("STATE"));
        } catch (Exception e) {
            logger.warn("No user");
            return false;
        } finally {
            r.unlock();
        }
    }

    /**
     * a.setState(b, state) 负责和b交流的a的那个session设置成state
     *
     * @param _name
     * @param state
     */
    public void setState(String _name, String state) {
        r.lock();
        try {
            SipSession session = sessions.get(_name);
            if (session != null && session.getAttribute("STATE") == null)
                session.setAttribute("STATE", "START");

            logger.info("change from " + sessions.get(_name).getAttribute("STATE") + " to " + state);

            sessions.get(_name).setAttribute("STATE", state);

            if (state.equals(END) || state.equals(IDLE) || state.equals(HOLDON_HOST)) {
                busy = false;
                oppo = null;
            } else {
                busy = true;
                oppo = _name; // 这时候本user的oppo应该就是_name
            }

            // 这块是想做什么？？#####################################
            SipUser user = users.get(_name);
            if (linkUser.containsKey(_name)) {
                for (Map.Entry<String, PriorityQueue<SipUser>> entry : linkUser.entrySet()) {
                    if (entry.getValue().contains(user)) {
                        // 这里是写错了？#######################################
                        entry.getValue().remove(user); //
                        entry.getValue().add(user); //
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            r.unlock();
        }
    }

    public boolean isBusy() {
        return busy;
    }

    public SipUser getAvaliable(String _name) {
        if (linkUser.containsKey(_name)) {
            SipUser cur = linkUser.get(_name).peek();

            // return cur; 为什么要这样写..######################
            if (cur != null)
                return cur;
            else
                return null;

        } else {
            return null;
        }
    }

    /**
     * 从sessions、realUser和linkUser中移除与_name相关的信息
     *
     * @param _name
     */
    public void clean(String _name) {
        if (_name == null)
            return;

        r.lock();
        try {
            sessions.remove(_name);
            realUser.remove(realUser.get(_name));
            realUser.remove(_name);
            linkUser.remove(_name);

            SipUser user = users.get(_name);
            Iterator<Map.Entry<String, PriorityQueue<SipUser>>> it = linkUser.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, PriorityQueue<SipUser>> entry = it.next();

                while (entry.getValue().contains(user)) {
                    entry.getValue().remove(user);
                }

                if (entry.getValue().isEmpty()) {
                    it.remove();
                }
            }

            logger.info(name + " clean " + _name);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            r.unlock();
        }
    }

    public void replace(String fromName, String toName) {
        if (fromName == null || toName == null)
            return;

        r.lock();
        try {
            // 从linkUser中取出fromName对应的优先级队列
            PriorityQueue<SipUser> q = linkUser.get(fromName);
            if (q != null) {
                // 我感觉这个地方有问题 map.remove(key)才对，q是value
                linkUser.remove(fromName);

                // 根据fromName和toName从users中取出对应的sipUser
                SipUser fromUser = users.get(fromName);
                SipUser toUser = users.get(toName);

                for (Map.Entry<String, PriorityQueue<SipUser>> entry : linkUser.entrySet()) {
                    // entry.getValue得到的是其中1个优先级队列
                    if (entry.getValue().contains(fromName)) {
                        entry.getValue().remove(fromUser);
                        entry.getValue().add(toUser);
                    }

                    while (entry.getValue().contains(fromUser))
                        entry.getValue().remove(fromUser);
                }

                // 将q的key从fromName改成了toName
                linkUser.put(toName, q);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            r.unlock();
        }
    }

    public void dealBye(String fromName, String toName) {
        if (fromName == null || toName == null)
            return;

        r.lock();
        try {
            linkUser.remove(fromName);
            PriorityQueue<SipUser> q = linkUser.get(toName);
            if (q != null) {
                SipUser fromUser = users.get(fromName);
                SipUser toUser = users.get(toName);
                q.remove(fromUser);
                for (Map.Entry<String, PriorityQueue<SipUser>> entry : linkUser.entrySet()) {
                    if (entry.getValue().contains(fromUser)) {
                        entry.getValue().remove(fromUser);
                        entry.getValue().add(toUser);
                    }

                    while (entry.getValue().contains(fromUser))
                        entry.getValue().remove(fromUser);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            r.unlock();
        }
    }
}