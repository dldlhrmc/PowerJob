package tech.powerjob.server.remote.server;

import tech.powerjob.common.exception.PowerJobException;
import tech.powerjob.common.utils.CommonUtils;
import tech.powerjob.common.utils.NetUtils;
import tech.powerjob.server.extension.LockService;
import tech.powerjob.server.persistence.remote.model.ServerInfoDO;
import tech.powerjob.server.persistence.remote.repository.ServerInfoRepository;
import com.google.common.base.Stopwatch;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * management server info, like heartbeat, server id etc
 *
 * @author tjq
 * @since 2021/2/21
 */
@Slf4j
@Service
public class ServerInfoService {

    private final String ip;
    private final long serverId;

    private final ServerInfoRepository serverInfoRepository;

    private static final long MAX_SERVER_CLUSTER_SIZE = 10000;

    private static final String SERVER_INIT_LOCK = "server_init_lock";
    private static final int SERVER_INIT_LOCK_MAX_TIME = 15000;

    public long getServerId() {
        return serverId;
    }

    @Autowired
    public ServerInfoService(LockService lockService, ServerInfoRepository serverInfoRepository) {

        this.ip = NetUtils.getLocalHost();
        this.serverInfoRepository = serverInfoRepository;

        Stopwatch sw = Stopwatch.createStarted();

        while (!lockService.tryLock(SERVER_INIT_LOCK, SERVER_INIT_LOCK_MAX_TIME)) {
            log.info("[ServerInfoService] waiting for lock: {}", SERVER_INIT_LOCK);
            CommonUtils.easySleep(100);
        }

        try {

            // register server then get server_id
            ServerInfoDO server = serverInfoRepository.findByIp(ip);
            if (server == null) {
                ServerInfoDO newServerInfo = new ServerInfoDO(ip);
                server = serverInfoRepository.saveAndFlush(newServerInfo);
            } else {
                serverInfoRepository.updateGmtModifiedByIp(ip, new Date());
            }

            if (server.getId() < MAX_SERVER_CLUSTER_SIZE) {
                this.serverId = server.getId();
            } else {
                this.serverId = retryServerId();
                serverInfoRepository.updateIdByIp(this.serverId, ip);
            }

        } catch (Exception e) {
            log.error("[ServerInfoService] init server failed", e);
            throw e;
        } finally {
            lockService.unlock(SERVER_INIT_LOCK);
        }

        log.info("[ServerInfoService] ip:{}, id:{}, cost:{}", ip, serverId, sw);
    }

    @Scheduled(fixedRate = 15000, initialDelay = 15000)
    public void heartbeat() {
        serverInfoRepository.updateGmtModifiedByIp(ip, new Date());
    }


    private long retryServerId() {

        List<ServerInfoDO> serverInfoList = serverInfoRepository.findAll();

        log.info("[ServerInfoService] current server record num in database: {}", serverInfoList.size());

        // clean inactive server record first
        if (serverInfoList.size() > MAX_SERVER_CLUSTER_SIZE) {

            // use a large time interval to prevent valid records from being deleted when the local time is inaccurate
            Date oneDayAgo = DateUtils.addDays(new Date(), -1);
            int delNum =serverInfoRepository.deleteByGmtModifiedBefore(oneDayAgo);
            log.warn("[ServerInfoService] delete invalid {} server info record before {}", delNum, oneDayAgo);

            serverInfoList = serverInfoRepository.findAll();
        }

        if (serverInfoList.size() > MAX_SERVER_CLUSTER_SIZE) {
            throw new PowerJobException(String.format("The powerjob-server cluster cannot accommodate %d machines, please rebuild another cluster", serverInfoList.size()));
        }

        Set<Long> uedServerIds = serverInfoList.stream().map(ServerInfoDO::getId).collect(Collectors.toSet());
        for (long i = 1; i <= MAX_SERVER_CLUSTER_SIZE; i++) {
            if (uedServerIds.contains(i)) {
                continue;
            }

            log.info("[ServerInfoService] ID[{}] is not used yet, try as new server id", i);
            return i;
        }
        throw new PowerJobException("impossible");
    }
}
