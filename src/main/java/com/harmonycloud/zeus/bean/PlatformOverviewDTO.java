package com.harmonycloud.zeus.bean;

import com.harmonycloud.caas.common.model.AlertSummaryDTO;
import com.harmonycloud.caas.common.model.middleware.ClusterQuotaDTO;
import com.harmonycloud.caas.common.model.middleware.MiddlewareBriefInfoDTO;
import com.harmonycloud.caas.common.model.middleware.MiddlewareOperatorDTO;
import lombok.Data;

import java.util.List;

/**
 * @author liyinlong
 * @since 2021/9/24 3:42 下午
 */
@Data
public class PlatformOverviewDTO {

    /**
     * 平台版本
     */
    private String zeusVersion;

    /**
     * 集群信息
     */
    private ClusterQuotaDTO clusterQuota;

    /**
     * 集群中间件实例数量列表
     */
    private List<MiddlewareBriefInfoDTO> briefInfoList;

    /**
     * 控制器信息
     */
    private MiddlewareOperatorDTO operatorDTO;

    /**
     * 告警信息
     */
    private AlertSummaryDTO alertSummary;

    /**
     * 审计列表
     */
    private List<BeanOperationAudit> auditList;
}
