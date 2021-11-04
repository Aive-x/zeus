package com.harmonycloud.zeus.service.components.impl;

import com.harmonycloud.caas.common.enums.ComponentsEnum;
import com.harmonycloud.caas.common.model.middleware.*;
import com.harmonycloud.zeus.annotation.Operator;
import com.harmonycloud.zeus.service.components.AbstractBaseOperator;
import com.harmonycloud.zeus.service.components.api.LoggingService;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * @author xutianhong
 * @Date 2021/10/29 4:16 下午
 */
@Service
@Operator(paramTypes4One = String.class)
public class LoggingServiceImpl extends AbstractBaseOperator implements LoggingService {
    @Override
    public boolean support(String name) {
        return ComponentsEnum.LOGGING.getName().equals(name);
    }

    @Override
    public void deploy(MiddlewareClusterDTO cluster) {
        //创建分区
        namespaceService.save(cluster.getId(), "logging", null);
        //发布elasticsearch
        super.deploy(cluster);
        //为es创建nodePort
        createNodePort(cluster);
        //发布logPilot
        logPilot(cluster);
    }

    @Override
    public void integrate(MiddlewareClusterDTO cluster) {
        MiddlewareClusterDTO existCluster = clusterService.findById(cluster.getId());
        existCluster.setLogging(cluster.getLogging());
        clusterService.updateCluster(existCluster);
    }

    @Override
    protected String getValues(String repository, MiddlewareClusterDTO cluster) {
        return "image.repository=" + repository +
                ",elasticsearch-operator.enabled=false" +
                ",elasticPassword=Hc@Cloud01" +
                ",storage.masterClass=local-path" +
                ",logging.collection.filelog.enable=false" +
                ",logging.collection.stdout.enable=false" +
                ",resources.master.limits.cpu=0.5" +
                ",resources.master.limits.memory=1Gi" +
                ",resources.master.requests.cpu=0.5" +
                ",resources.master.requests.memory=1Gi";
    }

    @Override
    protected void install(String setValues, MiddlewareClusterDTO cluster) {
        helmChartService.upgradeInstall("middleware-elasticsearch", "logging", setValues,
                componentsPath + File.separator + "elasticsearch", cluster);
    }

    @Override
    protected void updateCluster(MiddlewareClusterDTO cluster) {
        MiddlewareClusterLoggingInfo es = new MiddlewareClusterLoggingInfo();
        es.setHost(cluster.getHost());
        es.setUser("elastic");
        es.setPassword("Hc@Cloud01");
        es.setProtocol("http");
        es.setPort("30092");
        MiddlewareClusterLogging logging = new MiddlewareClusterLogging();
        logging.setElasticSearch(es);
        cluster.setLogging(logging);
        clusterService.updateCluster(cluster);
    }

    public void createNodePort(MiddlewareClusterDTO cluster){
        IngressDTO ingressDTO = new IngressDTO();
        ingressDTO.setExposeType("NodePort");
        ingressDTO.setMiddlewareType("elasticsearch");
        ingressDTO.setProtocol("TCP");
        List<ServiceDTO> serviceList = new ArrayList<>();
        ServiceDTO serviceDTO = new ServiceDTO();
        serviceDTO.setExposePort("30092");
        serviceDTO.setServiceName("middleware-elasticsearch-master");
        serviceDTO.setServicePort("9200");
        serviceDTO.setTargetPort("9200");
        serviceList.add(serviceDTO);
        ingressDTO.setServiceList(serviceList);
        ingressService.create(cluster.getId(), "logging", "middleware-elasticsearch", ingressDTO);
    }

    public void logPilot(MiddlewareClusterDTO cluster){
        String repository = getRepository(cluster);
        String setValues = "image.logpilotRepository=" + repository + "/log-pilot" +
                ",image.logstashRepository=" + repository + "/logstash";
        helmChartService.upgradeInstall("log", "logging", setValues,
                componentsPath + File.separator + "logging", cluster);
    }
}
