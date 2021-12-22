package com.harmonycloud.zeus.service.middleware;

import com.harmonycloud.caas.common.model.middleware.ScheduleBackup;
import com.harmonycloud.zeus.integration.cluster.bean.MysqlScheduleBackupCRD;

import java.util.List;

/**
 * @author xutianhong
 * @Date 2021/4/7 2:03 下午
 */
public interface MysqlScheduleBackupService {

    /**
     * 查询备份列表
     *
     * @param clusterId 集群id
     * @param namespace 命名空间
     * @param name      中间件名称
     * @return List<BackupDto>
     */
    List<ScheduleBackup> listScheduleBackup(String clusterId, String namespace, String name);

    /**
     * 创建定时备份
     *
     * @param clusterId         集群id
     * @param mysqlScheduleBackupCRD 定时备份信息
     * @return
     */
    void create(String clusterId, MysqlScheduleBackupCRD mysqlScheduleBackupCRD);

    /**
     * 删除定时备份
     *
     * @param clusterId 集群id
     * @param namespace 命名空间
     * @param name      中间件名称
     */
    void delete(String clusterId, String namespace, String name);

    /**
     * 更新备份规则
     * @param clusterId
     * @param mysqlScheduleBackupCRD
     */
    void update(String clusterId, MysqlScheduleBackupCRD mysqlScheduleBackupCRD);

    /**
     * 获取备份规则
     * @param clusterId
     * @param namespace
     * @param backupScheduleName
     * @return
     */
    MysqlScheduleBackupCRD get(String clusterId, String namespace, String backupScheduleName);
}