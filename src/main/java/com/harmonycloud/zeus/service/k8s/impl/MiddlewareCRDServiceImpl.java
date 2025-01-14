package com.harmonycloud.zeus.service.k8s.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.harmonycloud.zeus.integration.cluster.MiddlewareWrapper;
import com.harmonycloud.zeus.integration.cluster.bean.MiddlewareCRD;
import com.harmonycloud.zeus.integration.cluster.bean.MiddlewareInfo;
import com.harmonycloud.zeus.service.k8s.MiddlewareCRDService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.harmonycloud.caas.common.constants.NameConstant;
import com.harmonycloud.caas.common.enums.DictEnum;
import com.harmonycloud.caas.common.enums.ErrorMessage;
import com.harmonycloud.caas.common.enums.middleware.MiddlewareTypeEnum;
import com.harmonycloud.caas.common.exception.BusinessException;
import com.harmonycloud.caas.common.model.middleware.Middleware;
import com.harmonycloud.caas.common.model.middleware.MiddlewareQuota;
import com.harmonycloud.caas.common.model.middleware.PodInfo;
import com.harmonycloud.tool.date.DateUtils;

import lombok.extern.slf4j.Slf4j;

import static com.harmonycloud.caas.common.constants.middleware.MiddlewareConstant.PODS;

/**
 * @author xutianhong
 * @Date 2021/4/1 5:13 下午
 */
@Service
@Slf4j
public class MiddlewareCRDServiceImpl implements MiddlewareCRDService {

    @Autowired
    private MiddlewareWrapper middlewareWrapper;

    /**
     * 查询中间件列表
     *
     * @param clusterId
     *            集群id
     * @param namespace
     *            命名空间
     * @return List<Middleware>
     */
    @Override
    public List<Middleware> list(String clusterId, String namespace, String type) {
        List<MiddlewareCRD> middlewareCRDList = this.listCRD(clusterId, namespace, type);
        if (CollectionUtils.isEmpty(middlewareCRDList)) {
            return new ArrayList<>(0);
        }

        List<Middleware> middlewares = new ArrayList<>();
        // 封装数据
        middlewareCRDList.forEach(k8sMiddleware -> middlewares.add(convertMiddleware(k8sMiddleware)));
        return middlewares;
    }

    /**
     * 查询中间件列表
     *
     * @param clusterId 集群id
     * @param namespace 命名空间
     * @return List<MiddlewareCRD>
     */
    @Override
    public List<MiddlewareCRD> listCRD(String clusterId, String namespace, String type) {
        Map<String, String> label = null;
        if (StringUtils.isNotEmpty(type)) {
            label = new HashMap<>(1);
            label.put("type", MiddlewareTypeEnum.findByType(type).getMiddlewareCrdType());
        }
        List<MiddlewareCRD> middlewareCRDList = middlewareWrapper.list(clusterId, namespace, label);
        return middlewareCRDList;
    }

    @Override
    public List<MiddlewareCRD> listCR(String clusterId, String namespace, Map<String, String> label) {
        return middlewareWrapper.list(clusterId, namespace, label);
    }

    /**
     * 查询中间件详情
     *
     * @param clusterId 集群id
     * @param namespace 命名空间
     * @param type      中间件类型
     * @param name      中间件名称
     * @return
     */
    @Override
    public Middleware simpleDetail(String clusterId, String namespace, String type, String name) {
        MiddlewareCRD cr = getCR(clusterId, namespace, type, name);
        return simpleConvert(cr);
    }

    @Override
    public MiddlewareCRD getCR(String clusterId, String namespace, String type, String name) {
        if (MiddlewareTypeEnum.isType(type)) {
            String crdName = MiddlewareCRDService.getCrName(type, name);
            return middlewareWrapper.get(clusterId, namespace, crdName);
        } else {
            List<MiddlewareCRD> middlewareCRDList = middlewareWrapper.list(clusterId, namespace, null);
            middlewareCRDList = middlewareCRDList.stream().filter(mwCRD -> mwCRD.getSpec().getName().equals(name))
                .collect(Collectors.toList());
            if (CollectionUtils.isEmpty(middlewareCRDList)) {
                throw new BusinessException(DictEnum.MIDDLEWARE, name, ErrorMessage.NOT_EXIST);
            }
            return middlewareCRDList.get(0);
        }
    }

    @Override
    public MiddlewareCRD getCRAndCheckRunning(Middleware middleware) {
        MiddlewareCRD mw =
            getCR(middleware.getClusterId(), middleware.getNamespace(), middleware.getType(), middleware.getName());
        if (mw == null) {
            throw new BusinessException(DictEnum.MIDDLEWARE, middleware.getName(), ErrorMessage.NOT_EXIST);
        }
        if (!NameConstant.RUNNING.equalsIgnoreCase(mw.getStatus().getPhase())) {
            throw new BusinessException(ErrorMessage.MIDDLEWARE_CLUSTER_IS_NOT_RUNNING);
        }
        return mw;
    }

    /**
     * 封装middleware
     *
     * @param k8SMiddlewareCRD
     * @return Middleware
     */
    public Middleware convertMiddleware(MiddlewareCRD k8SMiddlewareCRD) {
        Middleware middleware = simpleConvert(k8SMiddlewareCRD);
        middleware.setVersion(k8SMiddlewareCRD.getMetadata().getResourceVersion());
        if (!CollectionUtils.isEmpty(k8SMiddlewareCRD.getMetadata().getLabels())) {
            StringBuilder sb = new StringBuilder();
            k8SMiddlewareCRD.getMetadata().getLabels()
                .forEach((k, v) -> sb.append(k).append("=").append(v).append(","));
            sb.deleteCharAt(sb.length() - 1);
            middleware.setLabels(sb.toString());
        }
        MiddlewareQuota middlewareQuota = new MiddlewareQuota();
        middlewareQuota.setNum(k8SMiddlewareCRD.getStatus().getReplicas());
        if (!CollectionUtils.isEmpty(k8SMiddlewareCRD.getStatus().getResources().getRequests())) {
            middlewareQuota.setCpu(k8SMiddlewareCRD.getStatus().getResources().getRequests().get("cpu"));
            middlewareQuota.setMemory(k8SMiddlewareCRD.getStatus().getResources().getRequests().get("memory"));
        }
        if (!CollectionUtils.isEmpty(k8SMiddlewareCRD.getStatus().getResources().getLimits())) {
            middlewareQuota.setCpu(k8SMiddlewareCRD.getStatus().getResources().getLimits().get("cpu"));
            middlewareQuota.setMemory(k8SMiddlewareCRD.getStatus().getResources().getLimits().get("memory"));
        }
        Map<String, MiddlewareQuota> middlewareQuotaMap = new HashMap<>();
        middlewareQuotaMap.put(middleware.getType(), middlewareQuota);
        middleware.setQuota(middlewareQuotaMap);
        List<PodInfo> podInfoList = new ArrayList<>();
        Map<String, List<MiddlewareInfo>> include = k8SMiddlewareCRD.getStatus().getInclude();
        if (CollectionUtils.isEmpty(include)){
            return middleware;
        }
        List<MiddlewareInfo> middlewareInfoList = k8SMiddlewareCRD.getStatus().getInclude().get("pods");
        if (CollectionUtils.isEmpty(middlewareInfoList)) {
            middleware.setPods(new ArrayList<>(0));
        } else {
            middlewareInfoList.forEach(middlewareInfo -> {
                PodInfo podInfo = new PodInfo();
                podInfo.setPodName(middlewareInfo.getName());
                podInfoList.add(podInfo);
            });
            middleware.setPods(podInfoList);
        }

        return middleware;
    }

    @Override
    public Middleware simpleConvert(MiddlewareCRD mw) {
        return mw == null ? null : new Middleware()
                .setName(mw.getSpec().getName())
                .setNamespace(mw.getMetadata().getNamespace())
                .setClusterId(mw.getMetadata().getClusterName())
                .setType(MiddlewareTypeEnum.findTypeByCrdType(mw.getSpec().getType()))
                .setStatus(mw.getStatus() != null ? mw.getStatus().getPhase() : "")
                .setReason(mw.getStatus() != null ? mw.getStatus().getReason() : "")
                .setCreateTime(DateUtils.parseUTCDate(mw.getMetadata().getCreationTimestamp()))
                .setPodNum(getPodNum(mw));
    }

    /**
     * 获取服务pod数量
     * @param mw
     * @return
     */
    private int getPodNum(MiddlewareCRD mw) {
        if ((mw.getStatus() != null && mw.getStatus().getInclude() != null && mw.getStatus().getInclude().get(PODS) != null)) {
            return mw.getStatus().getInclude().get(PODS).size();
        } else {
            return 0;
        }
    }
}
