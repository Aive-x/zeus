package com.harmonycloud.zeus.service.k8s.impl;

import com.harmonycloud.zeus.integration.cluster.MiddlewareBackupScheduleWrapper;
import com.harmonycloud.zeus.integration.cluster.bean.MiddlewareBackupScheduleCRD;
import com.harmonycloud.zeus.integration.cluster.bean.MiddlewareBackupScheduleList;
import com.harmonycloud.zeus.service.k8s.MiddlewareBackupScheduleCRDService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

/**
 * @author liyinlong
 * @since 2021/9/14 10:50 上午
 */
@Service
@Slf4j
public class MiddlewareBackupScheduleCRDServiceImpl implements MiddlewareBackupScheduleCRDService {

    @Autowired
    private MiddlewareBackupScheduleWrapper middlewareBackupScheduleWrapper;

    @Override
    public void create(String clusterId, MiddlewareBackupScheduleCRD middlewareBackupScheduleCRD) throws IOException {
        middlewareBackupScheduleWrapper.create(clusterId, middlewareBackupScheduleCRD);
    }

    @Override
    public void update(String clusterId, MiddlewareBackupScheduleCRD middlewareBackupScheduleCRD) throws IOException {
        middlewareBackupScheduleWrapper.update(clusterId, middlewareBackupScheduleCRD);
    }

    @Override
    public MiddlewareBackupScheduleCRD get(String clusterId, String namespace, String backupName) {
        return middlewareBackupScheduleWrapper.get(clusterId, namespace, backupName);
    }

    @Override
    public void delete(String clusterId, String namespace, String name) throws IOException {
        middlewareBackupScheduleWrapper.delete(clusterId, namespace, name);
    }

    @Override
    public MiddlewareBackupScheduleList list(String clusterId, String namespace, Map<String, String> labels) {
        return middlewareBackupScheduleWrapper.list(clusterId, namespace, labels);
    }
}
