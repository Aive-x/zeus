package com.harmonycloud.zeus.schedule;

import com.harmonycloud.caas.common.model.middleware.Middleware;
import com.harmonycloud.caas.common.model.middleware.MiddlewareClusterDTO;
import com.harmonycloud.zeus.operator.BaseOperator;
import com.harmonycloud.zeus.operator.impl.MysqlOperatorImpl;
import com.harmonycloud.zeus.service.middleware.impl.MiddlewareBackupServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * @author dengyulong
 * @date 2021/04/14
 * 中间件管理的异步任务
 */
@Slf4j
@Component
public class MiddlewareManageTask {

    /**
     * 异步创建中间件
     *
     * @param middleware 中间件信息
     * @param cluster    集群
     * @param operator   operator实现
     */
    @Async("taskExecutor")
    public void asyncCreate(Middleware middleware, MiddlewareClusterDTO cluster, BaseOperator operator) {
        operator.create(middleware, cluster);
    }

    /**
     * 异步修改中间件
     */
    @Async("taskExecutor")
    public void asyncUpdate(Middleware middleware, MiddlewareClusterDTO cluster, BaseOperator operator) {
        operator.update(middleware, cluster);
    }

    /**
     * 异步删除中间件
     */
    @Async("taskExecutor")
    public void asyncDelete(Middleware middleware, BaseOperator operator) {
        operator.delete(middleware);
    }

    /**
     * 异步主从切换
     */
    @Async("taskExecutor")
    public void asyncSwitch(Middleware middleware, BaseOperator operator) {
        operator.switchMiddleware(middleware);
    }

    /**
     * 异步创建mysql灾备实例
     * @param mysqlOperator
     * @param middleware
     */
    @Async("singleThreadExecutor")
    public void asyncCreateDisasterRecoveryMiddleware(MysqlOperatorImpl mysqlOperator, Middleware middleware){
        mysqlOperator.createDisasterRecoveryMiddleware(middleware);
    }

    /**
     * 创建恢复
     * @param clusterId
     * @param namespace
     * @param type
     * @param middlewareName
     * @param backupName
     * @param restoreName
     * @param backupService
     */
    @Async("singleThreadExecutor")
    public void asyncCreateBackupRestore(String clusterId, String namespace, String type, String middlewareName, String backupName, String restoreName, MiddlewareBackupServiceImpl backupService){
        try {
            backupService.tryCreateMiddlewareRestore(clusterId, namespace, type, middlewareName, backupName, restoreName);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
