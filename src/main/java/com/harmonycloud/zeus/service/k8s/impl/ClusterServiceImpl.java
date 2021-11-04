package com.harmonycloud.zeus.service.k8s.impl;

import static com.harmonycloud.caas.common.constants.NameConstant.*;
import static com.harmonycloud.caas.common.constants.middleware.MiddlewareConstant.PERSISTENT_VOLUME_CLAIMS;
import static com.harmonycloud.caas.common.constants.middleware.MiddlewareConstant.PODS;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import com.harmonycloud.caas.common.constants.NameConstant;
import com.harmonycloud.caas.common.enums.*;
import com.harmonycloud.zeus.integration.cluster.bean.*;
import com.harmonycloud.zeus.service.middleware.MiddlewareService;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.yaml.snakeyaml.Yaml;

import com.alibaba.fastjson.JSONObject;
import com.harmonycloud.caas.common.enums.middleware.StorageClassProvisionerEnum;
import com.harmonycloud.caas.common.exception.BusinessException;
import com.harmonycloud.caas.common.exception.CaasRuntimeException;
import com.harmonycloud.caas.common.model.PrometheusResponse;
import com.harmonycloud.caas.common.model.middleware.*;
import com.harmonycloud.caas.common.model.registry.HelmChartFile;
import com.harmonycloud.caas.common.util.ThreadPoolExecutorFactory;
import com.harmonycloud.tool.cmd.HelmChartUtil;
import com.harmonycloud.tool.date.DateUtils;
import com.harmonycloud.zeus.integration.cluster.ClusterWrapper;
import com.harmonycloud.zeus.integration.cluster.PrometheusWrapper;
import com.harmonycloud.zeus.integration.registry.bean.harbor.HelmListInfo;
import com.harmonycloud.zeus.service.k8s.*;
import com.harmonycloud.zeus.service.middleware.EsService;
import com.harmonycloud.zeus.service.middleware.MiddlewareInfoService;
import com.harmonycloud.zeus.service.registry.HelmChartService;
import com.harmonycloud.zeus.service.registry.RegistryService;
import com.harmonycloud.zeus.util.K8sClient;
import com.harmonycloud.zeus.util.MathUtil;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import lombok.extern.slf4j.Slf4j;

/**
 * @author dengyulong
 * @date 2021/03/25
 */
@Slf4j
@Service
public class ClusterServiceImpl implements ClusterService {

    /**
     * 默认存储限额
     */
    private static final String DEFAULT_STORAGE_LIMIT = "100Gi";

    @Autowired
    private ClusterWrapper clusterWrapper;
    @Autowired
    private ClusterCertService clusterCertService;
    @Autowired
    private K8sClient k8sClient;
    @Autowired
    private RegistryService registryService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private NamespaceService namespaceService;
    @Autowired
    private K8sDefaultClusterService k8SDefaultClusterService;
    @Autowired
    private EsService esService;
    @Autowired
    private HelmChartService helmChartService;
    @Autowired
    private MiddlewareInfoService middlewareInfoService;
    @Autowired
    private PrometheusWrapper prometheusWrapper;
    @Autowired
    private MiddlewareCRDService middlewareCRDService;
    @Autowired
    private IngressService ingressService;
    @Autowired
    private MiddlewareService middlewareService;
    @Autowired
    private ClusterComponentService clusterComponentService;

    @Value("${k8s.component.logging.es.user:elastic}")
    private String esUser;
    @Value("${k8s.component.logging.es.password:Hc@Cloud01}")
    private String esPassword;
    @Value("${k8s.component.logging.es.port:30092}")
    private String esPort;

    @Value("${k8s.component.components:/usr/local/zeus-pv/components}")
    private String componentsPath;
    @Value("${k8s.component.middleware:/usr/local/zeus-pv/middleware}")
    private String middlewarePath;

    @Override
    public MiddlewareClusterDTO get(String clusterId) {
        List<MiddlewareClusterDTO> clusterList = listClusters().stream()
            .filter(clusterDTO -> clusterDTO.getId().equals(clusterId)).collect(Collectors.toList());
        if (CollectionUtils.isEmpty(clusterList)) {
            throw new CaasRuntimeException(ErrorMessage.CLUSTER_NOT_FOUND);
        }
        return clusterList.get(0);
    }

    @Override
    public List<MiddlewareClusterDTO> listClusters() {
        return listClusters(false, null);
    }

    @Override
    public List<MiddlewareClusterDTO> listClusters(boolean detail, String key) {
        List<MiddlewareClusterDTO> clusters;
        List<MiddlewareCluster> clusterList = clusterWrapper.listClusters();
        if (clusterList.size() <= 0) {
            return new ArrayList<>(0);
        }
        clusters = clusterList.stream().map(c -> {
            MiddlewareClusterInfo info = c.getSpec().getInfo();
            MiddlewareClusterDTO cluster = new MiddlewareClusterDTO();
            BeanUtils.copyProperties(info, cluster);
            cluster.setId(K8sClient.getClusterId(c.getMetadata())).setHost(info.getAddress())
                .setName(c.getMetadata().getName()).setDcId(c.getMetadata().getNamespace())
                .setIngress(info.getIngress());
            if (!CollectionUtils.isEmpty(c.getMetadata().getAnnotations())) {
                cluster.setNickname(c.getMetadata().getAnnotations().get(NAME));
            }
            JSONObject attributes = new JSONObject();
            attributes.put(CREATE_TIME, DateUtils.parseUTCDate(c.getMetadata().getCreationTimestamp()));
            cluster.setAttributes(attributes);

            return SerializationUtils.clone(cluster);
        }).collect(Collectors.toList());
        if (StringUtils.isNotEmpty(key)){
            clusters = clusters.stream().filter(clusterDTO -> clusterDTO.getNickname().contains(key))
                .collect(Collectors.toList());
        }
        // 返回命名空间信息
        if (detail && clusters.size() > 0) {
            clusters.parallelStream().forEach(cluster -> {
                // 初始化集群信息
                initClusterAttributes(cluster);
                try {
                    List<Namespace> list = namespaceService.list(cluster.getId());
                    cluster.getAttributes().put(NS_COUNT, list.size());
                } catch (Exception e) {
                    cluster.getAttributes().put(NS_COUNT, 0);
                    log.error("集群：{}，查询命名空间列表异常", cluster.getId(), e);
                }
                //计算集群cpu和memory
                if (cluster.getMonitor() != null && cluster.getMonitor().getPrometheus() != null){
                    clusterResource(cluster);
                }
                //判断集群是否可删除
                cluster.setRemovable(checkDelete(cluster.getId()));
            });
        }
        return clusters;
    }

    @Override
    public void initClusterAttributes(List<MiddlewareClusterDTO> clusters) {
        if (CollectionUtils.isEmpty(clusters)) {
            return;
        }
        clusters.parallelStream().forEach(this::initClusterAttributes);
    }

    @Override
    public void initClusterAttributes(MiddlewareClusterDTO cluster) {
        if (!CollectionUtils.isEmpty(cluster.getAttributes()) && cluster.getAttributes().get(KUBELET_VERSION) != null) {
            return;
        }
        nodeService.setClusterVersion(cluster);
    }


    @Override
    public MiddlewareClusterDTO findById(String clusterId) {
        MiddlewareClusterDTO dto = get(clusterId);
        if (dto == null) {
            throw new CaasRuntimeException(ErrorMessage.CLUSTER_NOT_FOUND);
        }
        // 如果accessToken为空，尝试生成token
        if (StringUtils.isBlank(dto.getAccessToken())) {
            clusterCertService.generateTokenByCert(dto);
        }
        // 深拷贝对象返回，避免其他地方修改内容
        return SerializationUtils.clone(dto);
    }

    @Override
    public MiddlewareClusterDTO findByIdAndCheckRegistry(String clusterId) {
        MiddlewareClusterDTO cluster = findById(clusterId);
        if (cluster.getRegistry() == null || StringUtils.isBlank(cluster.getRegistry().getAddress())) {
            throw new IllegalArgumentException("harbor info is illegal");
        }
        return cluster;
    }

    @Override
    public void addCluster(MiddlewareClusterDTO cluster) {
        // 校验集群基本信息参数
        if (cluster == null || StringUtils.isAnyEmpty(cluster.getName(), cluster.getProtocol(), cluster.getHost())) {
            throw new IllegalArgumentException("cluster base info is null");
        }
        // 校验集群基本信息
        checkParams(cluster);
        // 校验集群是否已存在
        checkClusterExistent(cluster, false);
        cluster.setId(K8sClient.getClusterId(cluster));
        // 设置证书信息
        clusterCertService.setCertByAdminConf(cluster.getCert());

        // 校验registry
        registryService.validate(cluster.getRegistry());

        try {
            // 先添加fabric8客户端，否则无法用fabric8调用APIServer
            k8sClient.addK8sClient(cluster, false);
            K8sClient.getClient(cluster.getId()).namespaces().withName(DEFAULT).get();
        } catch (CaasRuntimeException ignore) {
        } catch (Exception e) {
            log.error("集群：{}，校验基本信息异常", cluster.getName(), e);
            // 移除fabric8客户端
            K8sClient.removeClient(cluster.getId());
            throw new BusinessException(DictEnum.CLUSTER, cluster.getName(), ErrorMessage.AUTH_FAILED);
        }
        // 保存证书
        try {
            clusterCertService.saveCert(cluster);
            // 若为第一个集群 则将clusterId, url, serviceAccount存入数据库
            if (k8SDefaultClusterService.get() == null) {
                k8SDefaultClusterService.create(cluster);
            }
        } catch (Exception e) {
            log.error("集群{}，保存证书异常", cluster.getId(), e);
        }
        // 安装middleware-controller
        try {
            List<HelmListInfo> helmInfos = helmChartService.listHelm("", "", cluster);
            if (helmInfos.stream().noneMatch(info -> "middleware-controller".equals(info.getName()))) {
                clusterComponentService.deploy(cluster.getId(), ComponentsEnum.MIDDLEWARE_CONTROLLER.getName());
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorMessage.HELM_INSTALL_MIDDLEWARE_CONTROLLER_FAILED);
        }
        // 保存集群
        MiddlewareCluster mw = convert(cluster);
        try {
            MiddlewareCluster c = clusterWrapper.create(mw);
            JSONObject attributes = new JSONObject();
            attributes.put(CREATE_TIME, DateUtils.parseUTCDate(c.getMetadata().getCreationTimestamp()));
            cluster.setAttributes(attributes);
        } catch (IOException e) {
            log.error("集群id：{}，添加集群异常", cluster.getId());
            throw new BusinessException(DictEnum.CLUSTER, cluster.getNickname(), ErrorMessage.ADD_FAIL);
        }
        // 创建mysql/es/redis/mq operator 并添加进数据库
        createOperator(cluster.getId());
        // 安装组件
        createComponents(cluster);
        //初始化集群索引模板
        ThreadPoolExecutorFactory.executor.execute(() -> {
            try {
                esService.initEsIndexTemplate();
                log.info("集群:{}索引模板初始化完成", cluster.getName());
            } catch (Exception e) {
                log.error("集群:{}索引模板初始化失败", cluster.getName(), e);
            }
        });
    }

    @Override
    public void updateCluster(MiddlewareClusterDTO cluster) {
        // 校验集群基本信息参数
        if (StringUtils.isAnyEmpty(cluster.getNickname())) {
            throw new IllegalArgumentException("cluster nickname is null");
        }
        checkParams(cluster);

        // 校验集群基本信息
        // 校验集群是否已存在
        MiddlewareClusterDTO oldCluster = findById(cluster.getId());
        if (oldCluster == null) {
            throw new BusinessException(DictEnum.CLUSTER, cluster.getNickname(), ErrorMessage.NOT_EXIST);
        }
        checkClusterExistent(cluster, true);
        // 设置证书信息
        clusterCertService.setCertByAdminConf(cluster.getCert());
        k8sClient.updateK8sClient(cluster);

        // 校验registry
        registryService.validate(cluster.getRegistry());

        // 校验es（包含重置es客户端）
        /*if (StringUtils.isNotBlank(cluster.getLogging().getElasticSearch().getHost())
            && (!esComponentService.checkEsConnection(cluster) || esComponentService.resetEsClient(cluster) == null)) {
            throw new BusinessException(DictEnum.ES_COMPONENT, cluster.getLogging().getElasticSearch().getAddress(),
                ErrorMessage.VALIDATE_FAILED);
        }*/

        // 只修改昵称，证书，ingress，制品服务，es
        oldCluster.setNickname(cluster.getNickname());
        oldCluster.setCert(cluster.getCert());
        oldCluster.setIngress(cluster.getIngress());
        oldCluster.setRegistry(cluster.getRegistry());
        oldCluster.setLogging(cluster.getLogging());

        update(oldCluster);
    }

    @Override
    public void update(MiddlewareClusterDTO cluster) {
        try {
            clusterWrapper.update(convert(cluster));
        } catch (IOException e) {
            log.error("集群{}的accessToken更新失败", cluster.getId());
            throw new BusinessException(DictEnum.CLUSTER, cluster.getNickname(), ErrorMessage.UPDATE_FAIL);
        }
    }

    private void checkParams(MiddlewareClusterDTO cluster) {
        if (cluster.getCert() == null || StringUtils.isEmpty(cluster.getCert().getCertificate())) {
            throw new IllegalArgumentException("cluster cert info is null");
        }

        // 校验集群使用的制品服务参数
        Registry registry = cluster.getRegistry();
        if (registry == null || StringUtils.isAnyEmpty(registry.getProtocol(), registry.getAddress(),
            registry.getChartRepo(), registry.getUser(), registry.getPassword())) {
            registry = new Registry();
            registry.setAddress("middleware.harmonycloud.cn").setProtocol("http").setPort(38080).setUser("admin")
                .setPassword("Hc@Cloud01").setType("harbor").setChartRepo("middleware");
            cluster.setRegistry(registry);
        }
        // 设置默认参数
        // 如果没有数据中心，默认用default命名空间
        if (StringUtils.isBlank(cluster.getDcId())) {
            cluster.setDcId(DEFAULT);
        }
        // 给端口设置默认值，https是443，http是80
        if (cluster.getPort() == null) {
            cluster.setPort(cluster.getProtocol().equalsIgnoreCase(Protocol.HTTPS.getValue()) ? 443 : 80);
        }
        if (cluster.getRegistry().getPort() == null) {
            cluster.getRegistry()
                .setPort(cluster.getRegistry().getProtocol().equalsIgnoreCase(Protocol.HTTPS.getValue()) ? 443 : 80);
        }
        
        // 设置ingress
        if (cluster.getIngress() != null && cluster.getIngress().getTcp() == null) {
            cluster.getIngress().setTcp(new MiddlewareClusterIngress.IngressConfig());
        }
        
        // 设置es信息
        if (cluster.getLogging() == null) {
            cluster.setLogging(new MiddlewareClusterLogging());
        }
        if (cluster.getLogging().getElasticSearch() == null) {
            cluster.getLogging().setElasticSearch(new MiddlewareClusterLoggingInfo());
        }
        if (StringUtils.isNotEmpty(cluster.getLogging().getElasticSearch().getHost())) {
            if (StringUtils.isEmpty(cluster.getLogging().getElasticSearch().getProtocol())) {
                cluster.getLogging().getElasticSearch().setProtocol(Protocol.HTTP.getValue().toLowerCase());
            }
            if (StringUtils.isBlank(cluster.getLogging().getElasticSearch().getPort())) {
                cluster.getLogging().getElasticSearch().setPort(esPort);
            }
            if (StringUtils.isAnyBlank(cluster.getLogging().getElasticSearch().getUser(),
                cluster.getLogging().getElasticSearch().getPassword())) {
                cluster.getLogging().getElasticSearch().setUser(esUser).setPassword(esPassword);
            }
        }
        
        // 设置存储限额
        if (cluster.getStorage() == null) {
            cluster.setStorage(new HashMap<>());
        }
        if (cluster.getStorage().get(SUPPORT) == null) {
            List<String> defaultSupportList = StorageClassProvisionerEnum.getDefaultSupportType();
            Map<String, String> support =
                defaultSupportList.stream().collect(Collectors.toMap(s -> s, s -> DEFAULT_STORAGE_LIMIT));
            cluster.getStorage().put(SUPPORT, support);
        }
    }

    @Override
    public void removeCluster(String clusterId) {
        if (!checkDelete(clusterId)){
            throw new BusinessException(ErrorMessage.CLUSTER_NOT_EMPTY);
        }
        MiddlewareClusterDTO cluster = get(clusterId);
        if (cluster == null) {
            return;
        }
        try {
            clusterWrapper.delete(cluster.getDcId(), cluster.getName());
        } catch (IOException e) {
            log.error("集群id：{}，删除集群异常", clusterId, e);
            throw new BusinessException(DictEnum.CLUSTER, cluster.getNickname(), ErrorMessage.DELETE_FAIL);
        }
        // 从map中移除
        k8SDefaultClusterService.delete(clusterId);
    }

    private void checkClusterExistent(MiddlewareClusterDTO cluster, boolean expectExisting) {
        // 获取已有集群信息
        List<MiddlewareClusterDTO> clusterList = new ArrayList<>();
        try {
            clusterList.addAll(listClusters());
        } catch (Exception e){
        }
        // 校验内存中集群信息
        if (expectExisting) {
            // 期望集群存在 && 实际不存在
            if (clusterList.stream().noneMatch(clusterDTO -> clusterDTO.getId().equals(cluster.getId()))) {
                throw new BusinessException(DictEnum.CLUSTER, cluster.getName(), ErrorMessage.NOT_EXIST);
            }
            // 如果nickname重名
            if (clusterList.stream()
                .anyMatch(c -> !c.getId().equals(cluster.getId()) && c.getNickname().equals(cluster.getNickname()))) {
                throw new BusinessException(DictEnum.CLUSTER, cluster.getNickname(), ErrorMessage.EXIST);
            }
        } else {
            // 获取所有集群
            for (MiddlewareClusterDTO c : clusterList) {
                // 集群名称
                if (c.getId().equals(cluster.getId())) {
                    throw new BusinessException(DictEnum.CLUSTER, cluster.getName(), ErrorMessage.EXIST);
                }
                // 集群昵称
                if (c.getNickname().equals(cluster.getNickname())) {
                    throw new BusinessException(DictEnum.CLUSTER, cluster.getNickname(), ErrorMessage.EXIST);
                }
                // APIServer地址
                if (c.getHost().equals(cluster.getHost())) {
                    throw new BusinessException(DictEnum.CLUSTER, cluster.getAddress(), ErrorMessage.EXIST);
                }
            }
        }
    }
    /**
     * 判断集群是否可以被删除
     */
    public boolean checkDelete(String clusterId){
        List<MiddlewareCRD> middlewareCRDList = middlewareCRDService.listCR(clusterId, null, null);
        if (!CollectionUtils.isEmpty(middlewareCRDList) && middlewareCRDList.stream().anyMatch(
                middlewareCRD -> !"escluster-middleware-elasticsearch".equals(middlewareCRD.getMetadata().getName())
                        && !"mysqlcluster-zeus-mysql".equals(middlewareCRD.getMetadata().getName()))) {
            return false;
        }
        return true;
    }

    private MiddlewareCluster convert(MiddlewareClusterDTO cluster) {
        ObjectMeta meta = new ObjectMeta();
        meta.setName(cluster.getName());
        meta.setNamespace(cluster.getDcId());
        Map<String, String> annotations = new HashMap<>();
        annotations.put(NAME, cluster.getNickname());
        meta.setAnnotations(annotations);
        MiddlewareClusterInfo clusterInfo = new MiddlewareClusterInfo();
        BeanUtils.copyProperties(cluster, clusterInfo);
        clusterInfo.setAddress(cluster.getHost());
        return new MiddlewareCluster().setMetadata(meta).setSpec(new MiddlewareClusterSpec().setInfo(clusterInfo));
    }

    public void createOperator(String clusterId) {
        File file = new File(middlewarePath);
        for (String name : file.list()) {
            ThreadPoolExecutorFactory.executor.execute(() -> {
                File f = new File(middlewarePath + File.separator + name);
                if (f.getAbsolutePath().contains(".tgz")) {
                    HelmChartFile chartFile = helmChartService.getHelmChartFromFile(null, null, f);
                    helmChartService.createOperator(middlewarePath, clusterId, chartFile);
                    middlewareInfoService.insert(chartFile, f);
                }
            });
        }
    }

    public void createComponents(MiddlewareClusterDTO cluster) {
        List<HelmListInfo> helmListInfos = helmChartService.listHelm("", "", cluster);
        // 安装local-path
        try {
            if (helmListInfos.stream().noneMatch(helm -> "local-path".equals(helm.getName()))) {
                clusterComponentService.deploy(cluster.getId(), ComponentsEnum.LOCAL_PATH.getName());
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorMessage.HELM_INSTALL_LOCAL_PATH_FAILED);
        }
        if (cluster.getMonitor() == null) {
            MiddlewareClusterMonitor monitor = new MiddlewareClusterMonitor();
            cluster.setMonitor(monitor);
        }
        // 安装prometheus
        try {
            if (cluster.getMonitor().getPrometheus() == null
                && helmListInfos.stream().noneMatch(helm -> "prometheus".equals(helm.getName()))) {
                clusterComponentService.deploy(cluster.getId(), ComponentsEnum.PROMETHEUS.getName());
            }
        } catch (Exception e) {
            log.error(ErrorMessage.HELM_INSTALL_PROMETHEUS_FAILED.getZhMsg());
        }
        // 安装ingress nginx
        try {
            if (cluster.getComponentsInstall().getIngress() && (cluster.getIngress() == null || StringUtils.isEmpty(cluster.getIngress().getAddress()))) {
                if (helmListInfos.stream().noneMatch(helm -> "ingress".equals(helm.getName()))) {
                    clusterComponentService.deploy(cluster.getId(), ComponentsEnum.INGRESS.getName());
                }
            }
        } catch (Exception e) {
            log.error(ErrorMessage.HELM_INSTALL_NGINX_INGRESS_FAILED.getZhMsg());
        }
        // 安装grafana
        try {
            if (cluster.getComponentsInstall().getGrafana() && (cluster.getMonitor().getGrafana() == null || cluster.getMonitor().getGrafana().getHost() == null)) {
                clusterComponentService.deploy(cluster.getId(), ComponentsEnum.GRAFANA.getName());
            }
        } catch (Exception e) {
            log.error(ErrorMessage.HELM_INSTALL_GRAFANA_FAILED.getZhMsg());
        }
        // 安装alertManager
        try {
            if (cluster.getComponentsInstall().getAlertManager() && cluster.getMonitor().getAlertManager() == null){
                clusterComponentService.deploy(cluster.getId(), ComponentsEnum.ALERTMANAGER.getName());
            }
        } catch (Exception e) {
            log.error(ErrorMessage.HELM_INSTALL_ALERT_MANAGER_FAILED.getZhMsg());
        }
        // 安装minio
        try {
            //创建minio分区
            if (cluster.getStorage() == null || !cluster.getStorage().containsKey("backup")){
                clusterComponentService.deploy(cluster.getId(), ComponentsEnum.MINIO.getName());
            }
        } catch (Exception e) {
            log.error(ErrorMessage.HELM_INSTALL_MINIO_FAILED.getZhMsg());
        }
        //安装日志组件
        try {
            if (cluster.getComponentsInstall().getLogging()){
                if (cluster.getLogging() == null || cluster.getLogging().getElasticSearch() == null
                        || cluster.getLogging().getElasticSearch().getHost() == null) {
                    clusterComponentService.deploy(cluster.getId(), ComponentsEnum.LOGGING.getName());
                }
                else if(cluster.getLogging().getElasticSearch().getLogCollect()){
                    clusterComponentService.deploy(cluster.getId(), ComponentsEnum.LOGPILOT.getName());
                }
            }
        } catch (Exception e) {
            log.error(ErrorMessage.HELM_INSTALL_LOG_FAILED.getZhMsg());
        }
        //更新
        this.update(cluster);
    }

    public void clusterResource(MiddlewareClusterDTO cluster){
        Map<String, String> query = new HashMap<>();
        Map<String, String> resource = new HashMap<>();
        ClusterQuotaDTO clusterQuotaDTO = new ClusterQuotaDTO();
        //获取cpu总量
        try {
            query.put("query", "sum(harmonycloud_node_cpu_total)");
            PrometheusResponse cpuTotal = prometheusWrapper.get(cluster.getId(), PROMETHEUS_API_VERSION, query);
            clusterQuotaDTO.setTotalCpu(Double.parseDouble(cpuTotal.getData().getResult().get(0).getValue().get(1)));
        } catch (Exception e){
            clusterQuotaDTO.setTotalCpu(0);
            log.error("集群查询cpu总量失败");
        }
        //获取cpu使用量
        try {
            query.put("query", "sum(harmonycloud_node_cpu_using)");
            PrometheusResponse cpuUsing = prometheusWrapper.get(cluster.getId(), PROMETHEUS_API_VERSION, query);
            clusterQuotaDTO.setUsedCpu(Double.parseDouble(cpuUsing.getData().getResult().get(0).getValue().get(1)));
        } catch (Exception e){
            clusterQuotaDTO.setUsedCpu(0);
            log.error("集群查询cpu使用量失败");
        }
        //获取memory总量
        try {
            query.put("query", "sum(harmonycloud_node_memory_total)");
            PrometheusResponse memoryTotal = prometheusWrapper.get(cluster.getId(), PROMETHEUS_API_VERSION, query);
            clusterQuotaDTO.setTotalMemory(Double.parseDouble(memoryTotal.getData().getResult().get(0).getValue().get(1)));
        } catch (Exception e){
            clusterQuotaDTO.setTotalMemory(0);
            log.error("集群查询memory总量失败");
        }
        //获取memory使用量
        try {
            query.put("query", "sum(harmonycloud_node_memory_using)");
            PrometheusResponse memoryUsing = prometheusWrapper.get(cluster.getId(), PROMETHEUS_API_VERSION, query);
            clusterQuotaDTO.setUsedMemory(Double.parseDouble(memoryUsing.getData().getResult().get(0).getValue().get(1)));
        } catch (Exception e){
            clusterQuotaDTO.setUsedMemory(0);
            log.error("集群查询memory使用量失败");
        }
        cluster.setClusterQuotaDTO(clusterQuotaDTO);
    }

    @Override
    public List<Namespace> getRegisteredNamespaceNum(List<MiddlewareClusterDTO> clusterDTOList) {
        List<Namespace> namespaces = new ArrayList<>();
        clusterDTOList.forEach(clusterDTO -> {
            namespaces.addAll(getRegisteredNamespaceNum(clusterDTO));
        });
        return namespaces;
    }

    @Override
    public ClusterQuotaDTO getClusterQuota(List<MiddlewareClusterDTO> clusterDTOList) {
        ClusterQuotaDTO clusterQuotaSum = new ClusterQuotaDTO();
        clusterDTOList.forEach(clusterDTO -> {
            ClusterQuotaDTO clusterQuota = getClusterQuota(clusterDTO);
            if (clusterQuota != null) {
                clusterQuotaSum.setTotalCpu(clusterQuotaSum.getTotalCpu() + clusterQuota.getTotalCpu());
                clusterQuotaSum.setUsedCpu(clusterQuotaSum.getUsedCpu() + clusterQuota.getUsedCpu());
                clusterQuotaSum.setTotalMemory(clusterQuotaSum.getTotalMemory() + clusterQuota.getTotalMemory());
                clusterQuotaSum.setUsedMemory(clusterQuotaSum.getUsedMemory() + clusterQuota.getUsedMemory());
            }
        });
        clusterQuotaSum.setCpuUsedPercent(MathUtil.calcPercent(clusterQuotaSum.getUsedCpu(), clusterQuotaSum.getTotalCpu()));
        clusterQuotaSum.setMemoryUsedPercent(MathUtil.calcPercent(clusterQuotaSum.getUsedMemory(), clusterQuotaSum.getTotalMemory()));
        return clusterQuotaSum;
    }

    @Override
    public List<MiddlewareResourceInfo> getMwResource(String clusterId) {
        //获取集群下所有中间件信息
        List<MiddlewareCRD> mwCrDList = middlewareCRDService.listCR(clusterId, null, null);

        Map<String, String> queryMap = new HashMap<>();
        String time = DateUtils.formatDate(new Date().getTime(), DateType.YYYY_MM_DD_T_HH_MM_SS_Z_SSS.getValue(),
            TimeZone.getTimeZone("GMT"));
        queryMap.put("step", "1s");
        queryMap.put("start", time);
        queryMap.put("end", time);
        List<MiddlewareResourceInfo> mwResourceInfoList = new ArrayList<>();
        mwCrDList.forEach(mwCrd -> ThreadPoolExecutorFactory.executor.execute(() -> {
            try {
                Middleware middleware = middlewareService.detail(clusterId, mwCrd.getMetadata().getNamespace(), mwCrd.getSpec().getName(), mwCrd.getSpec().getType());
                MiddlewareResourceInfo middlewareResourceInfo = new MiddlewareResourceInfo();
                BeanUtils.copyProperties(middleware, middlewareResourceInfo);
                Map<String, String> finalQueryMap = new HashMap<>(queryMap);
                StringBuilder pods = getPodName(mwCrd);
                
                //查询cpu配额
                String cpuRequestQuery = "sum(kube_pod_container_resource_requests_cpu_cores{pod=~\"" + pods.toString()
                        + "\",namespace=\"" + mwCrd.getMetadata().getNamespace() + "\"})";
                finalQueryMap.put("query", cpuRequestQuery);
                PrometheusResponse cpuRequest =
                        prometheusWrapper.get(clusterId, NameConstant.PROMETHEUS_API_VERSION_RANGE, finalQueryMap);
                if (!CollectionUtils.isEmpty(cpuRequest.getData().getResult())){
                    middlewareResourceInfo.setPerMinCpu(Double.parseDouble(cpuRequest.getData().getResult().get(0).getValues().get(0).get(1)));
                }
                //查询cpu每5分钟平均用量
                String per5MinCpuUsedQuery = "sum by (container)(avg_over_time(container_cpu_usage_seconds_total{pod=~\"" + pods.toString()
                        + "\",namespace=\"" + mwCrd.getMetadata().getNamespace() + "\"}[5m]))";
                finalQueryMap.put("query", per5MinCpuUsedQuery);
                PrometheusResponse per5MinCpuUsed =
                        prometheusWrapper.get(clusterId, NameConstant.PROMETHEUS_API_VERSION_RANGE, finalQueryMap);
                if (!CollectionUtils.isEmpty(per5MinCpuUsed.getData().getResult())){
                    middlewareResourceInfo.setPer5MinCpu(Double.parseDouble(per5MinCpuUsed.getData().getResult().get(0).getValues().get(0).get(1)));
                }
                //查询memory配额
                String memoryRequestQuery = "sum(kube_pod_container_resource_requests_memory_bytes{pod=~\"" + pods.toString()
                        + "\",namespace=\"" + mwCrd.getMetadata().getNamespace() + "\"})";
                finalQueryMap.put("query", memoryRequestQuery);
                PrometheusResponse memoryRequest =
                        prometheusWrapper.get(clusterId, NameConstant.PROMETHEUS_API_VERSION_RANGE, finalQueryMap);
                if (!CollectionUtils.isEmpty(memoryRequest.getData().getResult())){
                    middlewareResourceInfo.setPerMinMemory(Double.parseDouble(memoryRequest.getData().getResult().get(0).getValues().get(0).get(1)));
                }
                //查询memory每5分钟平均用量
                String per5MinMemoryUsedQuery = "sum by (container)(avg_over_time(container_memory_working_set_bytes{pod=~\"" + pods.toString()
                        + "\",namespace=\"" + mwCrd.getMetadata().getNamespace() + "\"}[5m]))";
                finalQueryMap.put("query", per5MinMemoryUsedQuery);
                PrometheusResponse per5MinMemoryUsed =
                        prometheusWrapper.get(clusterId, NameConstant.PROMETHEUS_API_VERSION_RANGE, finalQueryMap);
                if (!CollectionUtils.isEmpty(per5MinMemoryUsed.getData().getResult())){
                    middlewareResourceInfo.setPer5MinMemory(Double.parseDouble(per5MinMemoryUsed.getData().getResult().get(0).getValues().get(0).get(1)));
                }
                //查询storage每5分钟平均用量
                //获取pvc
                StringBuilder pvcs = getPvcs(mwCrd);
                String per5MinStorageUsedQuery =
                    "sum by (container)(avg_over_time(kubelet_volume_stats_used_bytes{persistentvolumeclaim=~\""
                        + pvcs.toString() + "\",namespace=\"" + mwCrd.getMetadata().getNamespace() + "\"}[5m]))";
                finalQueryMap.put("query", per5MinStorageUsedQuery);
                PrometheusResponse per5MinStorageUsed =
                    prometheusWrapper.get(clusterId, NameConstant.PROMETHEUS_API_VERSION_RANGE, finalQueryMap);
                if (!CollectionUtils.isEmpty(per5MinStorageUsed.getData().getResult())) {
                    middlewareResourceInfo.setPer5MinStorage(
                        Double.parseDouble(per5MinStorageUsed.getData().getResult().get(0).getValues().get(0).get(1)));
                }
                mwResourceInfoList.add(middlewareResourceInfo);
            } catch (Exception e){

            }
        }));
        return mwResourceInfoList;
    }
    
    public StringBuilder getPodName(MiddlewareCRD mwCrd) {
        StringBuilder pods = new StringBuilder();
        List<MiddlewareInfo> podInfo = mwCrd.getStatus().getInclude().get(PODS);
        if (!CollectionUtils.isEmpty(podInfo)) {
            for (MiddlewareInfo middlewareInfo : podInfo) {
                pods.append(middlewareInfo.getName()).append("|");
            }
        }
        return pods;
    }

    public StringBuilder getPvcs(MiddlewareCRD mwCrd){
        StringBuilder pvcs = new StringBuilder();
        List<MiddlewareInfo> pvcInfo = mwCrd.getStatus().getInclude().get(PERSISTENT_VOLUME_CLAIMS);
        if (!CollectionUtils.isEmpty(pvcInfo)) {
            for (MiddlewareInfo middlewareInfo : pvcInfo) {
                pvcs.append(middlewareInfo.getName()).append("|");
            }
        }
        return pvcs;
    }

    /**
     * 获取集群注册的分区
     *
     * @param clusterDTO 集群dto
     * @return
     */
    public List<Namespace> getRegisteredNamespaceNum(MiddlewareClusterDTO clusterDTO) {
        if (clusterDTO == null) {
            return new ArrayList();
        }
        List<Namespace> namespaces = namespaceService.list(clusterDTO.getId(), false, false, false, null);
        return namespaces.stream().filter(namespace -> namespace.isRegistered()).collect(Collectors.toList());
    }

    /**
     * 获取集群资源配额及使用量
     *
     * @param clusterDTO 集群dto
     * @return
     */
    public ClusterQuotaDTO getClusterQuota(MiddlewareClusterDTO clusterDTO) {
        return clusterDTO.getClusterQuotaDTO();
    }

    public static void main(String[] args){
        Map<String, Integer> map = new HashMap<>();
        map.put("test", 0);
        for (int i = 1; i<=5; ++i) {
            int finalI = i;
            ThreadPoolExecutorFactory.executor.execute(() -> {
                Map<String, Integer> finalMap = new HashMap<>(map);
                finalMap.put("test1", finalI);
                log.info(JSONObject.toJSONString(finalMap));
            });
        }
    }


}
