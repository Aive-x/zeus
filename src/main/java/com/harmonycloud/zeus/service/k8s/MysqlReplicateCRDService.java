package com.harmonycloud.zeus.service.k8s;

import com.harmonycloud.zeus.integration.cluster.bean.MysqlReplicateCRD;

import java.io.IOException;

/**
 *
 * @author liyinlong
 * @date 2021/8/11 3:00 下午
 */
public interface MysqlReplicateCRDService {

    /**
     * 创建mysql源实例和灾备实例的复制关系
     * @param clusterId 灾备实例集群id
     * @param mysqlReplicateCRD mysql复制关系
     */
    void createMysqlReplicate(String clusterId, MysqlReplicateCRD mysqlReplicateCRD) throws IOException;

    /**
     * 修改mysql源实例和灾备实例的复制关系
     * @param clusterId 灾备实例集群id
     * @param mysqlReplicateCRD mysql复制关系
     */
    void replaceMysqlReplicate(String clusterId, MysqlReplicateCRD mysqlReplicateCRD) throws IOException;

    /**
     * 删除mysql源实例和灾备实例的复制关系
     * @param clusterId 灾备实例集群id
     * @param namespace 灾备实例分区
     * @param name 复制关系名称
     * @throws IOException
     */
    void deleteMysqlReplicate(String clusterId, String namespace, String name) throws IOException;

    /**
     * 获取灾备复制信息
     * @param clusterId 集群id
     * @param namespace 分区id
     * @param name 中间件名称
     * @return
     */
    MysqlReplicateCRD getMysqlReplicate(String clusterId, String namespace, String name);
}
