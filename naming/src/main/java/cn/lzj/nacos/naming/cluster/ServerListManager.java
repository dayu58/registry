package cn.lzj.nacos.naming.cluster;

import cn.lzj.nacos.api.common.Constants;
import cn.lzj.nacos.naming.config.NetConfig;
import cn.lzj.nacos.naming.misc.GlobalExecutor;
import cn.lzj.nacos.naming.misc.Message;
import cn.lzj.nacos.naming.misc.ServerStatusSynchronizer;
import cn.lzj.nacos.naming.misc.Synchronizer;
import cn.lzj.nacos.naming.utils.SystemUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


import javax.annotation.PostConstruct;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component("serverListManager")
public class ServerListManager {

    //所有的server节点，包括健康的和非健康的
    private List<Server> servers = new ArrayList<>();

    private List<ServerChangeListener> listeners = new ArrayList<>();

    //可用的server节点
    private List<Server> healthyServers = new ArrayList<>();

    private Synchronizer synchronizer = new ServerStatusSynchronizer();

    //Map<serverIp:serverPort,timestamps>
    private Map<String, Long> distroBeats = new ConcurrentHashMap<>(16);

    //为了保存着最新的server更新心跳时间后的数据
    private Map<String, List<Server>> distroConfig = new ConcurrentHashMap<>();

    //心跳消息前缀
    private final static String LOCALHOST_SITE="cluster_status";

    @Autowired
    private NetConfig netConfig;

    @PostConstruct
    public void init() {
        GlobalExecutor.registerServerListUpdater(new ServerListUpdater());
        GlobalExecutor.registerServerStatusReporter(new ServerStatusReporter(), 5);
    }

    /**
     * 添加观察者
     * @param listener
     */
    public void listen(ServerChangeListener listener) {
        listeners.add(listener);
    }


    /**
     * 返回配置文件读取的server列表
     * @return
     */
    private List<Server> refreshServerList() {
        List<Server> result = new ArrayList<>();
        List<String> serverList=new ArrayList<>();
        try {
            serverList = SystemUtils.readClusterConf();
            log.info("server列表的ip是: {}", serverList);
        } catch (IOException e) {
            log.error("读取集群配置文件失败", e);
        }
        if(CollectionUtils.isNotEmpty(serverList)){
            for(int i=0;i<serverList.size();i++){
                String ip;
                int port;
                String server=serverList.get(i);
                ip=server.split(Constants.IP_PORT_SPLITER)[0];
                port= Integer.parseInt(server.split(Constants.IP_PORT_SPLITER)[1]);

                Server member=new Server();
                member.setIp(ip);
                member.setServePort(port);
                result.add(member);
            }
        }
        return result;
    }


    public class ServerListUpdater implements Runnable{

        @Override
        public void run() {
            try {
                List<Server> refreshedServers = refreshServerList();
                List<Server> oldServers = servers;

                boolean changed=false;

                List<Server> newServers = (List<Server>) CollectionUtils.subtract(refreshedServers, oldServers);
                if (CollectionUtils.isNotEmpty(newServers)) {
                    //改变了配置文件log，增加了新的集群节点
                    servers.addAll(newServers);
                    changed = true;
                    log.info("server列表更新了, new: {} servers: {}", newServers.size(), newServers);
                }

                List<Server> deadServers = (List<Server>) CollectionUtils.subtract(oldServers, refreshedServers);
                if (CollectionUtils.isNotEmpty(deadServers)) {
                    //删除了旧的集群节点
                    servers.removeAll(deadServers);
                    changed = true;
                    log.info("server列表更新了, dead: {}, servers: {}", deadServers.size(), deadServers);
                }

                if (changed) {
                    notifyListeners();
                }

            }catch (Exception e){
                log.error("error while updating server list.", e);
            }
        }

    }


    private class ServerStatusReporter implements Runnable {

        @Override
        public void run() {
            try{

                //自己当前的ip加端口
                String serverAddr=netConfig.getServerIp()+ Constants.IP_PORT_SPLITER+netConfig.getServerPort();
                //心跳信息
                String status=LOCALHOST_SITE+"#"+serverAddr+"#"+System.currentTimeMillis()+"#";

                //检查一下该server下收到的心跳，把自己的健康server列表更新
                checkHeartBeat();

                //发送心跳给自己
                onReceiveServerStatus(status);

                List<Server> allServers=servers;
                if(allServers.size()>0){
                    //给所有的server都发一次心跳
                    for(Server server:allServers){
                        if(server.getKey().equals(serverAddr)){
                            //跳过自己本身
                            continue;
                        }
                        Message message=new Message();
                        message.setData(status);
                        //给别的server发送自己的状态
                        synchronizer.send(server.getKey(), message);

                    }
                }
            }catch (Exception e) {
                log.error("发送server状态的过程中出现了错误:", e);
            } finally {
                //3s后再执行一次
                GlobalExecutor.registerServerStatusReporter(this, Constants.SERVER_STATUS_SYNCHRONIZATION_PERIOD_MILLIS);
            }
        }
    }

    /**
     * 检查健康与非健康的实例，更改健康server实例的列表
     */
    private void checkHeartBeat() {

        log.debug("检查server集群间的心跳");

        List<Server> allServers=distroConfig.get(LOCALHOST_SITE);
        List<Server> newHealthyList=new ArrayList<>(allServers.size());

        long now=System.currentTimeMillis();
        //健康的servers列表有没有发生改变的标志
        boolean changed=false;
        for(Server s:allServers){
            Long lastBeat=distroBeats.get(s.getKey());
            if(null==lastBeat){
                continue;
            }
            s.setAlive(now-lastBeat<Constants.SERVER_EXPIRED_MILLS);
            if(s.isAlive()&&!healthyServers.contains(s)){
                //原来不在健康的server列表里，加入进去
                newHealthyList.add(s);
                changed=true;
            }else if(!s.isAlive()&&healthyServers.contains(s)){
                //原来在健康的server列表里面，现在是不存活状态了，把这个server实例剔除
                changed=true;
            }
        }
        if(changed){
            healthyServers=newHealthyList;
            notifyListeners();
        }


    }

    /**
     * 别的server发送状态后调用该方法，加锁是因为是异步发送http请求的
     * @param serverStatus
     */
    public synchronized void onReceiveServerStatus(String serverStatus) {

        log.info("收到集群间的心跳:"+serverStatus);
        if(serverStatus.length()==0){
            return;
        }

        List<Server> tempServerList=new ArrayList<>();

        //cluster_status#192.168.153.1:9002#1586336129841#
        String[] params=serverStatus.split("#");
        Server server=new Server();
        server.setSite(params[0]);
        server.setIp(params[1].split(Constants.IP_PORT_SPLITER)[0]);
        server.setServePort(Integer.parseInt(params[1].split(Constants.IP_PORT_SPLITER)[1]));
        server.setLastRefTime(Long.parseLong(params[2]));

        Long lastBeat=distroBeats.get(server.getKey());
        long now = System.currentTimeMillis();
        if(null!=lastBeat){
            //不是第一次发送心跳,太久才发一次心跳(15s)的话也把该server节点设为非存活状态，等下次再发送一次心跳间隔小于15s才设置为存活状态
            server.setAlive(now-lastBeat<Constants.SERVER_EXPIRED_MILLS);
        }
        distroBeats.put(server.getKey(),now);

        Date date=new Date(Long.parseLong(params[2]));
        //格式化时间戳
        server.setLastRefTimeStr(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date));
        List<Server> list=distroConfig.get(server.getSite());
        if(list==null||list.size()<=0){
            list=new ArrayList<>();
            list.add(server);
            distroConfig.put(server.getSite(),list);
        }

        List<Server> tmpServerList = new ArrayList<>();
        //更改原来存在distroConfig中server更新后的时间戳
        for(Server s:list){
            String serverId=s.getKey();
            String newServerId=server.getKey();
            //原来已经存在了
            if(serverId.equals(newServerId)){
                //更新发送心跳的server最新的数据
                tmpServerList.add(server);
                continue;
            }
            //把不是发送心跳的server列表加回去
            tmpServerList.add(s);
        }
        //覆盖原来的list
        distroConfig.put(server.getSite(),tempServerList);
    }

    private void notifyListeners() {
        GlobalExecutor.notifyServerListChange(new Runnable() {
            @Override
            public void run() {
                //通知其他节点集群列表更改了
                for (ServerChangeListener listener : listeners) {
                    listener.onChangeServerList(servers);
                    listener.onChangeHealthyServerList(healthyServers);
                }
            }
        });
    }
}
