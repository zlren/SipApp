package com.zczg.app;

import java.util.Map;

import javax.servlet.sip.SipSession;

import org.apache.log4j.Logger;

public class Shower extends Thread {
	
	private static Logger logger = Logger.getLogger(Shower.class);
	
	public Map<String, SipUser> users;
	private Map<String, String> memberMap;

	public Shower(Map<String, SipUser> users) {
		this.users = users;
	}
	
	@Override
	public void run() {
		try {
			
			while (true) {
				SipUser alice = users.get("alice");
				SipUser bob = users.get("bob");
				
				try {
					logger.info("alice的状态" + alice.sessions.get("bob").getAttribute("STATE"));
					logger.info("bob的状态" + bob.sessions.get("alice").getAttribute("STATE"));
				} catch (Exception e) {
					// TODO: handle exception
				}
				
				if (alice != null) {
					SipSession sipSession = alice.sessions.get("0123");
					if (sipSession != null) {
						String aliceState = (String) sipSession.getAttribute("STATE");
						if (aliceState != null) {
							logger.info("alice的状态为 " + aliceState);
						}
					}
				}
				
				if (bob != null) {
					SipSession sipSession = bob.sessions.get("0123");
					if (sipSession != null) {
						String bobState = (String) sipSession.getAttribute("STATE");
						if (bobState != null) {
							logger.info("bob的状态为 " + bobState);
						}
					}
				}
				
				SipConf sipConf = (SipConf)users.get("0123");
				if(sipConf != null) {
					memberMap = sipConf.memberMap;
					String aliceConfState = memberMap.get("alice");
					String bobConfState = memberMap.get("bob");
					
					if (aliceConfState != null) {
						logger.info("alice在会议中的状态为" + aliceConfState);
					}
					
					if (bobConfState != null) {
						logger.info("bob在会议中的状态为" + bobConfState);
					}
				}
				
				logger.info("******************************************************");
				sleep(5000);
			}
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
