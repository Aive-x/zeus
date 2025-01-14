package com.harmonycloud.zeus.service.k8s;

import java.util.List;
import java.util.Map;

import com.harmonycloud.caas.common.enums.middleware.MiddlewareTypeEnum;
import com.harmonycloud.caas.common.model.middleware.Middleware;
import com.harmonycloud.zeus.integration.cluster.bean.MiddlewareCRD;

/**
 * @author xutianhong
 * @Date 2021/4/1 5:06 下午
 */
public interface MiddlewareCRDService {

    /**
     * 查询中间件列表
     *
     * @param clusterId 集群id
     * @param namespace 命名空间
     * @return List<Middleware>
     */
    List<Middleware> list(String clusterId, String namespace, String type);

    /**
     * 查询中间件列表
     *
     * @param clusterId 集群id
     * @param namespace 命名空间
     * @return List<MiddlewareCRD>
     */
    List<MiddlewareCRD> listCRD(String clusterId, String namespace, String type);

    /**
     * 查询中间件列表
     *
     * @param clusterId 集群id
     * @param namespace 命名空间
     * @param label     标签
     * @return List<MiddlewareCRD>
     */
    List<MiddlewareCRD> listCR(String clusterId, String namespace, Map<String, String> label);

    /**
     * 查询中间件简单详情
     *
     * @param clusterId 集群id
     * @param namespace 命名空间
     * @param type      中间件类型
     * @param name      中间件名称
     * @return
     */
    Middleware simpleDetail(String clusterId, String namespace, String type, String name);

    /**
     * 查询中间件cr
     *
     * @param clusterId 集群id
     * @param namespace 命名空间
     * @param type      中间件类型
     * @param name      中间件名称
     * @return
     */
    MiddlewareCRD getCR(String clusterId, String namespace, String type, String name);

    /**
     * 查询中间件cr并且检验是否在运行中
     *
     * @param middleware 中间件参数，clusterId/namespace/type/name
     * @return
     */
    MiddlewareCRD getCRAndCheckRunning(Middleware middleware);

    /**
     * crd简单转middleware
     *
     * @param mw crd
     * @return
     */
    Middleware simpleConvert(MiddlewareCRD mw);


    /**
     * 获取cr名称
     *
     * @param type           中间件类型
     * @param middlewareName 中间件名称
     * @return
     */
    static String getCrName(String type, String middlewareName) {
        return MiddlewareTypeEnum.findByType(type).getMiddlewareCrdType() + "-" + middlewareName;
    }

}
