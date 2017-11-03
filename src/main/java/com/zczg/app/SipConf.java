package com.zczg.app;

import com.zczg.util.ConfData;
import org.apache.log4j.Logger;
import org.slf4j.LoggerFactory;

import javax.media.mscontrol.MsControlException;
import javax.media.mscontrol.join.Joinable.Direction;
import javax.media.mscontrol.mixer.MediaMixer;
import javax.media.mscontrol.networkconnection.NetworkConnection;
import javax.servlet.sip.Address;
import java.util.HashMap;
import java.util.Map;

public class SipConf extends SipUser {

    private static Logger logger = Logger.getLogger(SipConf.class);

    private final static String PRE_FULL = "PRE_FULL";
    private final static String PRE_HALF = "PRE_HALF";
    private final static String FULL_FULL = "FULL_FULL";
    private final static String FULL_HALF = "FULL_HALF";

    final static String HOLD_CONF = "HOLD_CONF";
    final static String IN_CONF = "IN_CONF";

    String confKey; // 会议号
    String host; // 主持人
    MediaMixer mixer; // 会议的mixer
    private String confType; // 会议类型 01:预编程全双工 00:预编程半双工 11:可编程全双工 10:可编程半双工
    Map<String, String> memberMap; // 与会人列表

    public SipConf(String confKey, MediaMixer mixer, String host, String sipAddr) {
        this.name = confKey;
        this.confKey = confKey;
        this.host = host;
        this.mixer = mixer;
        this.memberMap = new HashMap<String, String>();
        this.sipadd = sipAddr;

        char c = confKey.charAt(1);
        if (ConfData.isPreConf(confKey)) {
            if (c == '0') {
                this.confType = SipConf.PRE_HALF;
                logger.info(confKey + "预编程半双工");
            } else {
                this.confType = SipConf.PRE_FULL;
                logger.info(confKey + "预编程全双工");
            }
        } else if (ConfData.isFullConf(confKey)) {
            if (c == '0') {
                this.confType = SipConf.FULL_HALF;
                logger.info(confKey + "可编程半双工");
            } else {
                this.confType = SipConf.FULL_FULL;
                logger.info(confKey + "可编程全双工");
            }
        }
    }

    /**
     * 加入会议
     *
     * @param userName
     * @param conn
     */
    public void joinConf(String userName, NetworkConnection conn) {
        // 全双工会议
        Direction direction;
        if (this.confType.equals(SipConf.PRE_FULL) || this.confType.equals(SipConf.FULL_FULL)) {
            direction = Direction.DUPLEX;
        } else {
            direction = Direction.RECV;
        }

        try {
            conn.join(direction, mixer);
            memberMap.put(userName, SipConf.IN_CONF);
        } catch (MsControlException ignored) {
        }
    }

    /**
     * 退出会议
     *
     * @param userName
     * @param conn
     */
    public void exitConf(String userName, NetworkConnection conn) {
        try {
            if (memberMap.get(userName).equals(SipConf.IN_CONF)) {
                conn.unjoin(this.mixer);
            }
//			conn.release();
            memberMap.remove(userName);
            if (memberMap.size() == 0) {
                this.mixer.release();
            }
        } catch (MsControlException ignored) {

        }
    }

    public SipConf(String _name, String _sipadd, String _ip, String _port, Integer _priority) {
        super(_name, _sipadd, _ip, _port, _priority);
    }

    public SipConf(String _name, String _sipadd, String _ip, String _port, Integer _priority, Boolean _wait,
                   String _preforwardAlways, String _preforwardBusy, String _preforwardTimeout, Map<String, SipUser>
                           _users, Address _address) {
        super(_name, _sipadd, _ip, _port, _priority, _wait, _preforwardAlways, _preforwardBusy, _preforwardTimeout,
                _users, _address);
    }
}
