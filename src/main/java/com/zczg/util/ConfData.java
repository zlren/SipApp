package com.zczg.util;


import org.apache.log4j.Logger;

import java.util.*;

public class ConfData {

    private static Logger logger = Logger.getLogger(ConfData.class);


    public static Map<String, Set<String>> preConfMap; // 预编程会议成员列表

    /**
     * 初始化关于会议相关的参数，从配置文件读取
     *
     * @param propConf
     */
    public static void init(Properties propConf) {

        // 预编程会议列表
        String[] preArray = propConf.getProperty("conf.pre.list").split(";");
        ConfData.preConfMap = new HashMap<String, Set<String>>();

        for (String p : preArray) {
            int k = p.indexOf(":");
            String confKey = p.substring(0, k);
            String members = p.substring(k + 1, p.length());
            Set<String> memberSet = new HashSet<String>();
            Collections.addAll(memberSet, members.split("%"));

            ConfData.preConfMap.put(confKey, memberSet);
        }

        logger.info("pre conf list :");
        for (String key : preConfMap.keySet()) {
            System.out.print(key + ": ");

            Set<String> set = preConfMap.get(key);
            for (String s : set) {
                System.out.print(s + " ");
            }
            System.out.println();
        }
    }


    /**
     * 判断是否为预编程会议
     *
     * @param confKey
     * @return
     */
    public static boolean isPreConf(String confKey) {
        if (confKey.length() == 4 && confKey.charAt(0) == '0') {
            return true;
        }
        return false;
    }


    /**
     * 判断是否为可编程会议
     *
     * @param confKey
     * @return
     */
    public static boolean isFullConf(String confKey) {
        if (confKey.length() == 4 && confKey.charAt(0) == '1') {
            return true;
        }
        return false;
    }

    /**
     * 判断是否为会议号
     *
     * @param confKey
     * @return
     */
    public static boolean isConf(String confKey) {
        return isPreConf(confKey) || isFullConf(confKey);
    }
}
