package com.harmonycloud.zeus.operator.impl;

import static com.harmonycloud.caas.common.constants.MinioConstant.BACKUP;
import static com.harmonycloud.caas.common.constants.MinioConstant.MINIO;
import static com.harmonycloud.caas.common.constants.NameConstant.RESOURCES;
import static com.harmonycloud.caas.common.constants.NameConstant.STORAGE;
import static com.harmonycloud.caas.common.constants.NameConstant.TYPE;
import static com.harmonycloud.caas.common.constants.middleware.MiddlewareConstant.MIDDLEWARE_EXPOSE_NODEPORT;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSONArray;
import com.harmonycloud.caas.common.constants.MysqlConstant;
import com.harmonycloud.caas.common.enums.DateType;
import com.harmonycloud.caas.common.model.middleware.*;
import com.harmonycloud.zeus.integration.cluster.MysqlClusterWrapper;
import com.harmonycloud.zeus.integration.cluster.bean.*;
import com.harmonycloud.zeus.operator.BaseOperator;
import com.harmonycloud.zeus.service.k8s.MysqlReplicateCRDService;
import com.harmonycloud.zeus.service.k8s.ServiceService;
import com.harmonycloud.zeus.service.k8s.StorageClassService;
import com.harmonycloud.zeus.service.middleware.BackupService;
import com.harmonycloud.zeus.service.middleware.MiddlewareBackupService;
import com.harmonycloud.zeus.service.middleware.impl.MiddlewareBackupServiceImpl;
import com.harmonycloud.zeus.service.middleware.impl.MiddlewareServiceImpl;
import com.harmonycloud.zeus.util.CronUtils;
import com.harmonycloud.zeus.util.DateUtil;
import com.harmonycloud.zeus.util.ServiceNameConvertUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;

import com.alibaba.fastjson.JSONObject;
import com.harmonycloud.caas.common.constants.NameConstant;
import com.harmonycloud.caas.common.enums.DictEnum;
import com.harmonycloud.caas.common.enums.ErrorMessage;
import com.harmonycloud.caas.common.enums.middleware.MiddlewareTypeEnum;
import com.harmonycloud.caas.common.exception.BusinessException;
import com.harmonycloud.zeus.annotation.Operator;
import com.harmonycloud.zeus.integration.minio.MinioWrapper;
import com.harmonycloud.zeus.operator.api.MysqlOperator;
import com.harmonycloud.zeus.operator.miiddleware.AbstractMysqlOperator;
import com.harmonycloud.zeus.service.k8s.ClusterService;
import com.harmonycloud.zeus.service.middleware.ScheduleBackupService;
import com.harmonycloud.tool.date.DateUtils;
import com.harmonycloud.tool.encrypt.PasswordUtils;
import com.harmonycloud.tool.uuid.UUIDUtils;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import lombok.extern.slf4j.Slf4j;

/**
 * @author dengyulong
 * @date 2021/03/23
 * 处理mysql逻辑
 */
@Slf4j
@Operator(paramTypes4One = Middleware.class)
public class MysqlOperatorImpl extends AbstractMysqlOperator implements MysqlOperator {

    @Autowired
    private MysqlClusterWrapper mysqlClusterWrapper;
    @Autowired
    private BackupService backupService;
    @Autowired
    private ScheduleBackupService scheduleBackupService;
    @Autowired
    private MinioWrapper minioWrapper;
    @Autowired
    private ClusterService clusterService;
    @Autowired
    private MysqlReplicateCRDService mysqlReplicateCRDService;
    @Autowired
    private MiddlewareServiceImpl middlewareService;
    @Autowired
    private BaseOperatorImpl baseOperator;
    @Autowired
    private StorageClassService storageClassService;

    @Override
    public boolean support(Middleware middleware) {
        return MiddlewareTypeEnum.MYSQL == MiddlewareTypeEnum.findByType(middleware.getType());
    }

    @Override
    public void replaceValues(Middleware middleware, MiddlewareClusterDTO cluster, JSONObject values) {
        // 替换通用的值
        replaceCommonValues(middleware, cluster, values);
        MiddlewareQuota quota = middleware.getQuota().get(middleware.getType());
        replaceCommonResources(quota, values.getJSONObject(RESOURCES));
        replaceCommonStorages(quota, values);

        //添加业务数据库
        if (middleware.getBusinessDeploy() != null && !middleware.getBusinessDeploy().isEmpty()) {
            JSONArray array = values.getJSONArray("businessDeploy");
            middleware.getBusinessDeploy().forEach(mysqlBusinessDeploy -> array.add(JSONUtil.parse(mysqlBusinessDeploy)));

        }

        // mysql参数
        JSONObject mysqlArgs = values.getJSONObject("args");
        if (StringUtils.isBlank(middleware.getPassword())) {
            middleware.setPassword(PasswordUtils.generateCommonPassword(10));
        }
        log.info("mysql特有参数：", mysqlArgs);
        mysqlArgs.put("root_password", middleware.getPassword());
        if (StringUtils.isNotBlank(middleware.getCharSet())) {
            mysqlArgs.put("character_set_server", middleware.getCharSet());
        }
        if (middleware.getPort() != null) {
            mysqlArgs.put("server_port", middleware.getPort());
        }
        if (middleware.getMysqlDTO() != null) {
            MysqlDTO mysqlDTO = middleware.getMysqlDTO();
            if (mysqlDTO.getReplicaCount() != null && mysqlDTO.getReplicaCount() > 0) {
                int replicaCount = mysqlDTO.getReplicaCount();
                replicaCount++;
                values.put(MysqlConstant.REPLICA_COUNT, replicaCount);
            }
            if (mysqlDTO.getOpenDisasterRecoveryMode() != null && mysqlDTO.getOpenDisasterRecoveryMode()) {
                mysqlArgs.put(MysqlConstant.IS_SOURCE, mysqlDTO.getIsSource());
                mysqlArgs.put(MysqlConstant.RELATION_CLUSTER_ID, mysqlDTO.getRelationClusterId());
                mysqlArgs.put(MysqlConstant.RELATION_NAMESPACE, mysqlDTO.getRelationNamespace());
                mysqlArgs.put(MysqlConstant.RELATION_NAME, mysqlDTO.getRelationName());
                mysqlArgs.put(MysqlConstant.RELATION_ALIAS_NAME, mysqlDTO.getRelationAliasName());
                mysqlArgs.put(MysqlConstant.CHART_NAME, middleware.getChartName());
            }
            if (StringUtils.isNotBlank(mysqlDTO.getType())) {
                values.put(MysqlConstant.SPEC_TYPE, mysqlDTO.getType());
            }
        }
        //配置mysql环境变量
        if (!CollectionUtils.isEmpty(middleware.getEnvironment())) {
            middleware.getEnvironment().forEach(mysqlEnviroment -> mysqlArgs.put(mysqlEnviroment.getName(),mysqlEnviroment.getValue()));
        }
        // 备份恢复的创建
        if (StringUtils.isNotEmpty(middleware.getBackupFileName())) {
            BackupStorageProvider backupStorageProvider = backupService.getStorageProvider(middleware);
            values.put("storageProvider", JSONObject.toJSON(backupStorageProvider));
        }
    }

    @Override
    public Middleware convertByHelmChart(Middleware middleware, MiddlewareClusterDTO cluster) {
        JSONObject values = helmChartService.getInstalledValues(middleware, cluster);
        convertCommonByHelmChart(middleware, values);
        convertStoragesByHelmChart(middleware, middleware.getType(), values);

        // 处理mysql的特有参数
        if (values != null) {
            convertResourcesByHelmChart(middleware, middleware.getType(), values.getJSONObject(RESOURCES));
            JSONObject args = values.getJSONObject("args");
            if (args == null){
                args = values.getJSONObject("mysqlArgs");
            }
            middleware.setPassword(args.getString("root_password"));
            middleware.setCharSet(args.getString("character_set_server"));
            middleware.setPort(args.getIntValue("server_port"));

            MysqlDTO mysqlDTO = new MysqlDTO();
            mysqlDTO.setReplicaCount(args.getIntValue(MysqlConstant.REPLICA_COUNT));
            // 设置是否允许备份
            MiddlewareQuota mysql = middleware.getQuota().get("mysql");
            mysqlDTO.setIsLvmStorage(storageClassService.checkLVMStorage(cluster.getId(), middleware.getNamespace(), mysql.getStorageClassName()));
            middleware.setMysqlDTO(mysqlDTO);
            // 获取关联实例信息
            Boolean isSource = args.getBoolean(MysqlConstant.IS_SOURCE);
            if (isSource != null) {
                mysqlDTO.setOpenDisasterRecoveryMode(true);
                mysqlDTO.setIsSource(isSource);
                mysqlDTO.setReplicaCount(args.getIntValue(MysqlConstant.REPLICA_COUNT));
                //获取关联实例信息
                String relationClusterId = args.getString(MysqlConstant.RELATION_CLUSTER_ID);
                String relationNamespace = args.getString(MysqlConstant.RELATION_NAMESPACE);
                String relationName = args.getString(MysqlConstant.RELATION_NAME);
                String relationAliasName = args.getString(MysqlConstant.RELATION_ALIAS_NAME);
                String chartName = args.getString(MysqlConstant.CHART_NAME);
                mysqlDTO.setRelationClusterId(relationClusterId);
                mysqlDTO.setRelationNamespace(relationNamespace);
                mysqlDTO.setRelationName(relationName);
                mysqlDTO.setRelationAliasName(relationAliasName);
                mysqlDTO.setRelationExist(baseOperator.checkIfExist(relationNamespace, relationName,cluster));
                middleware.setChartName(chartName);

                MysqlReplicateCRD mysqlReplicate;
                if (isSource) {
                    mysqlReplicate = mysqlReplicateCRDService.getMysqlReplicate(relationClusterId, relationNamespace, relationName);
                } else {
                    mysqlReplicate = mysqlReplicateCRDService.getMysqlReplicate(cluster.getId(), middleware.getNamespace(), middleware.getName());
                }
                if (mysqlReplicate != null && mysqlReplicate.getStatus() != null) {
                    mysqlDTO.setPhase(mysqlReplicate.getStatus().getPhase());
                    mysqlDTO.setCanSwitch(mysqlReplicate.getSpec().isEnable());
                    List<MysqlReplicateStatus.PodStatus> podStatuses = mysqlReplicate.getStatus().getSlaves();
                    if (!CollectionUtils.isEmpty(podStatuses)) {
                        MysqlReplicateStatus.PodStatus podStatus = podStatuses.get(0);
                        String lastUpdateTime = podStatus.getLastUpdateTime();
                        mysqlDTO.setLastUpdateTime(DateUtil.utc2Local(lastUpdateTime, DateType.YYYY_MM_DD_HH_MM_SS.getValue(), DateType.YYYY_MM_DD_HH_MM_SS.getValue()));
                    }
                }
            }
        }
        return middleware;
    }

    @Override
    public void create(Middleware middleware, MiddlewareClusterDTO cluster) {
        super.create(middleware, cluster);
        // 将服务通过NodePort对外暴露
        MysqlDTO mysqlDTO = middleware.getMysqlDTO();
        if (mysqlDTO != null) {
            if (mysqlDTO.getIsSource() != null && !mysqlDTO.getIsSource()) {
                //当前实例为灾备实例，灾备实例只读，不可写入，为该实例创建只读对外服务
                tryCreateOpenService(middleware, ServiceNameConvertUtil.convertMysql(middleware.getName(), true), true);
            } else {
                //当前实例为源实例或普通实例，实例可读写，为该实例创建可读写对外服务
                tryCreateOpenService(middleware, ServiceNameConvertUtil.convertMysql(middleware.getName(), false), true);
            }
        }
        // 创建灾备实例
        middlewareManageTask.asyncCreateDisasterRecoveryMiddleware(this, middleware);
    }

    @Override
    public void update(Middleware middleware, MiddlewareClusterDTO cluster) {
        if (cluster == null) {
            cluster = clusterService.findById(middleware.getClusterId());
        }
        StringBuilder sb = new StringBuilder();

        // 实例扩容
        if (middleware.getQuota() != null && middleware.getQuota().get(middleware.getType()) != null) {
            MiddlewareQuota quota = middleware.getQuota().get(middleware.getType());
            // 设置limit的resources
            setLimitResources(quota);
            if (StringUtils.isNotBlank(quota.getCpu())) {
                sb.append("resources.requests.cpu=").append(quota.getCpu()).append(",resources.limits.cpu=")
                    .append(quota.getLimitCpu()).append(",");
            }
            if (StringUtils.isNotBlank(quota.getMemory())) {
                sb.append("resources.requests.memory=").append(quota.getMemory()).append(",resources.limits.memory=")
                    .append(quota.getLimitMemory()).append(",");
            }
        }

        // 修改密码
        if (StringUtils.isNotBlank(middleware.getPassword())) {
            sb.append("args.root_password=").append(middleware.getPassword()).append(",");
        }

        // 修改关联实例信息
        MysqlDTO mysqlDTO = middleware.getMysqlDTO();
        if (mysqlDTO != null && mysqlDTO.getOpenDisasterRecoveryMode() != null) {
            sb.append(String.format("%s.%s=%s,", MysqlConstant.ARGS, MysqlConstant.IS_SOURCE, mysqlDTO.getIsSource()));
            sb.append(String.format("%s.%s=%s,", MysqlConstant.ARGS, MysqlConstant.RELATION_CLUSTER_ID, mysqlDTO.getRelationClusterId()));
            sb.append(String.format("%s.%s=%s,", MysqlConstant.ARGS, MysqlConstant.RELATION_NAMESPACE, mysqlDTO.getRelationNamespace()));
            sb.append(String.format("%s.%s=%s,", MysqlConstant.ARGS, MysqlConstant.RELATION_NAME, mysqlDTO.getRelationName()));
            sb.append(String.format("%s.%s=%s,", MysqlConstant.ARGS, MysqlConstant.RELATION_ALIAS_NAME, mysqlDTO.getRelationAliasName()));
        }

        if (mysqlDTO != null && mysqlDTO.getType() != null) {
            sb.append(String.format("%s=%s,", MysqlConstant.SPEC_TYPE, mysqlDTO.getType()));
        }

        if (mysqlDTO != null && mysqlDTO.getType() != null) {
            sb.append(String.format("%s=%s,", MysqlConstant.SPEC_TYPE, mysqlDTO.getType()));
        }

        // 没有修改，直接返回
        if (sb.length() == 0) {
            return;
        }
        // 去掉末尾的逗号
        sb.deleteCharAt(sb.length() - 1);
        // 更新helm
        helmChartService.upgrade(middleware, sb.toString(), cluster);
        // 创建灾备实例
        this.createDisasterRecoveryMiddleware(middleware);
    }

    @Override
    public void delete(Middleware middleware) {
        this.deleteDisasterRecoveryInfo(middleware);
        super.delete(middleware);
        // 删除备份
        String backupName = getBackupName(middleware);
        List<Backup> backupList = backupService.listBackup(middleware.getClusterId(), middleware.getNamespace());
        backupList.forEach(backup -> {
            if (!backup.getName().contains(backupName)) {
                return;
            }
            try {
                deleteBackup(middleware, backup.getBackupFileName(), backup.getName());
            } catch (Exception e) {
                log.error("集群：{}，命名空间：{}，mysql中间件：{}，删除mysql备份异常", middleware.getClusterId(), middleware.getNamespace(),
                    middleware.getName(), e);
            }
        });
        // 删除定时备份任务
        scheduleBackupService.delete(middleware.getClusterId(), middleware.getNamespace(), middleware.getName());
    }

    /**
     * 查询备份列表
     */
    @Override
    public List<MysqlBackupDto> listBackups(Middleware middleware) {

        // 获取Backup
        String name = getBackupName(middleware);
        List<Backup> backupList = backupService.listBackup(middleware.getClusterId(), middleware.getNamespace());
        backupList = backupList.stream().filter(backup -> backup.getName().contains(name)).collect(Collectors.toList());

        // 获取当前备份中的状态
        List<MysqlBackupDto> mysqlBackupDtoList = new ArrayList<>();

        backupList.forEach(backup -> {
            MysqlBackupDto mysqlBackupDto = new MysqlBackupDto();
            if (!"Complete".equals(backup.getPhase())) {
                mysqlBackupDto.setStatus(backup.getPhase());
                mysqlBackupDto.setBackupFileName("");
            } else {
                mysqlBackupDto.setStatus("Complete");
                mysqlBackupDto.setBackupFileName(backup.getBackupFileName());
            }
            mysqlBackupDto.setBackupName(backup.getName());
            mysqlBackupDto.setDate(DateUtils.parseUTCDate(backup.getBackupTime()));
            mysqlBackupDto.setPosition("minio(" + backup.getEndPoint() + "/" + backup.getBucketName() + ")");
            mysqlBackupDto.setType("all");
            mysqlBackupDtoList.add(mysqlBackupDto);
        });

        // 根据时间降序
        mysqlBackupDtoList.sort(
            (o1, o2) -> o1.getDate() == null ? -1 : o2.getDate() == null ? -1 : o2.getDate().compareTo(o1.getDate()));
        return mysqlBackupDtoList;
    }

    private String getBackupName(Middleware middleware) {
        return middleware.getClusterId() + "-" + middleware.getNamespace() + "-" + middleware.getName();
    }

    /**
     * 查询定时备份配置
     */
    @Override
    public ScheduleBackupConfig getScheduleBackupConfig(Middleware middleware) {
        List<ScheduleBackup> scheduleBackupList = scheduleBackupService.listScheduleBackup(middleware.getClusterId(),
            middleware.getNamespace(), middleware.getName());
        if (CollectionUtils.isEmpty(scheduleBackupList)) {
            return null;
        }
        ScheduleBackup scheduleBackup = scheduleBackupList.get(0);
        ScheduleBackupConfig scheduleBackupConfig = new ScheduleBackupConfig();
        scheduleBackupConfig.setCron(scheduleBackup.getSchedule());
        scheduleBackupConfig.setKeepBackups(scheduleBackup.getKeepBackups());
        scheduleBackupConfig.setNextBackupDate(calculateNextDate(scheduleBackup));
        return scheduleBackupConfig;
    }

    /**
     * 创建定时备份
     */
    @Override
    public void createScheduleBackup(Middleware middleware, Integer keepBackups, String cron) {
        // 校验是否运行中
        middlewareCRDService.getCRAndCheckRunning(middleware);

        Minio minio = getMinio(middleware);
        BackupTemplate backupTemplate = new BackupTemplate().setClusterName(middleware.getName())
            .setStorageProvider(new BackupStorageProvider().setMinio(minio));

        ScheduleBackupSpec spec =
            new ScheduleBackupSpec().setSchedule(CronUtils.parseMysqlUtcCron(cron)).setBackupTemplate(backupTemplate).setKeepBackups(keepBackups);
        ObjectMeta metaData = new ObjectMeta();
        metaData.setName(getBackupName(middleware));
        Map<String, String> labels = new HashMap<>();
        labels.put("controllername", "backup-schedule-controller");
        metaData.setLabels(labels);
        metaData.setNamespace(middleware.getNamespace());
        metaData.setClusterName(middleware.getName());

        ScheduleBackupCRD scheduleBackupCRD =
            new ScheduleBackupCRD().setKind("MysqlBackupSchedule").setSpec(spec).setMetadata(metaData);
        scheduleBackupService.create(middleware.getClusterId(), scheduleBackupCRD);
    }

    /**
     * 创建备份
     */
    @Override
    public void createBackup(Middleware middleware) {
        // 校验是否运行中
        middlewareCRDService.getCRAndCheckRunning(middleware);

        String backupName = getBackupName(middleware) + "-" + UUIDUtils.get8UUID();

        BackupSpec spec = new BackupSpec().setClusterName(middleware.getName())
            .setStorageProvider(new BackupStorageProvider().setMinio(getMinio(middleware)));

        ObjectMeta metaData = new ObjectMeta();
        metaData.setName(backupName);
        Map<String, String> labels = new HashMap<>(1);
        labels.put("controllername", "backup-controller");
        metaData.setLabels(labels);
        metaData.setNamespace(middleware.getNamespace());
        metaData.setClusterName(middleware.getName());

        BackupCRD backupCRD = new BackupCRD().setKind("MysqlBackup").setSpec(spec).setMetadata(metaData);
        backupService.create(middleware.getClusterId(), backupCRD);
    }

    /**
     * 删除备份文件
     */
    @Override
    public void deleteBackup(Middleware middleware, String backupFileName, String backupName) throws Exception {
        backupService.delete(middleware.getClusterId(), middleware.getNamespace(), backupName);
        minioWrapper.removeObject(getMinio(middleware), backupFileName);
    }

    @Override
    public void switchMiddleware(Middleware middleware) {
        MysqlCluster mysqlCluster = mysqlClusterWrapper.get(middleware.getClusterId(), middleware.getNamespace(), middleware.getName());
        if (mysqlCluster == null) {
            throw new BusinessException(DictEnum.MYSQL_CLUSTER, middleware.getName(), ErrorMessage.NOT_EXIST);
        }
        if (!NameConstant.RUNNING.equalsIgnoreCase(mysqlCluster.getStatus().getPhase())) {
            throw new BusinessException(ErrorMessage.MIDDLEWARE_CLUSTER_IS_NOT_RUNNING);
        }
        // 手动切换
        if (handSwitch(middleware, mysqlCluster)) {
            return;
        }
        // 自动切换
        autoSwitch(middleware, mysqlCluster);

    }

    @Override
    public List<String> getConfigmapDataList(ConfigMap configMap) {
        return new ArrayList<>(Arrays.asList(configMap.getData().get("my.cnf.tmpl").split("\n")));
    }

    /**
     * 构建新configmap
     */
    @Override
    public Map<String, String> configMap2Data(ConfigMap configMap) {
        String dataString = configMap.getData().get("my.cnf.tmpl");
        Map<String, String> dataMap = new HashMap<>();
        String[] datalist = dataString.split("\n");
        for (String data : datalist) {
            if (!data.contains("=") || data.contains("#")) {
                continue;
            }
            data = data.replaceAll(" ", "");
            // 特殊处理
            if (data.contains("plugin-load")) {
                dataMap.put("plugin-load", data.replace("plugin-load=", ""));
                continue;
            }
            String[] keyValue = data.split("=");
            dataMap.put(keyValue[0].replaceAll(" ", ""), keyValue[1]);
        }
        return dataMap;
    }

    @Override
    public void editConfigMapData(CustomConfig customConfig, List<String> data){
        for (int i = 0; i < data.size(); ++i) {
            if (data.get(i).contains(customConfig.getName())) {
                String temp = StringUtils.substring(data.get(i), data.get(i).indexOf("=") + 1, data.get(i).length());
                if (data.get(i).replace(" ", "").replace(temp, "").replace("=", "").equals(customConfig.getName())){
                    data.set(i, data.get(i).replace(temp, customConfig.getValue()));
                }
            }
        }
    }

    /**
     * 转换data为map形式
     */
    @Override
    public void updateConfigData(ConfigMap configMap, List<String> data) {
        // 构造新configmap
        StringBuilder temp = new StringBuilder();
        for (String str : data) {
            {
                temp.append(str).append("\n");
            }
        }
        configMap.getData().put("my.cnf.tmpl", temp.toString());
    }

    /**
     * 手动切换
     */
    private boolean handSwitch(Middleware middleware, MysqlCluster mysqlCluster) {
        // 不等于null，自动切换，无需处理
        if (middleware.getAutoSwitch() != null) {
            // false为无需切换，true为已切换
            return false;
        }
        String masterName = null;
        String slaveName = null;
        for (Status.Condition cond : mysqlCluster.getStatus().getConditions()) {
            if ("master".equalsIgnoreCase(cond.getType())) {
                masterName = cond.getName();
            } else if ("slave".equalsIgnoreCase(cond.getType())) {
                slaveName = cond.getName();
            }
        }
        if (masterName == null || slaveName == null) {
            throw new BusinessException(ErrorMessage.MIDDLEWARE_CLUSTER_POD_ERROR);
        }
        mysqlCluster.getSpec().getClusterSwitch().setFinished(false).setSwitched(false).setMaster(slaveName);
        try {
            mysqlClusterWrapper.update(middleware.getClusterId(), middleware.getNamespace(), mysqlCluster);
        } catch (IOException e) {
            log.error("集群id:{}，命名空间:{}，mysql集群:{}，手动切换异常", middleware.getClusterId(), middleware.getNamespace(),
                middleware.getName(), e);
            throw new BusinessException(DictEnum.MYSQL_CLUSTER, middleware.getName(), ErrorMessage.SWITCH_FAILED);
        }
        return true;
    }

    /**
     * 自动切换
     */
    private void autoSwitch(Middleware middleware, MysqlCluster mysqlCluster) {
        boolean changeStatus = false;
        if (mysqlCluster.getSpec().getPassiveSwitched() == null) {
            if (!middleware.getAutoSwitch()) {
                changeStatus = true;
                mysqlCluster.getSpec().setPassiveSwitched(true);
            }
        } else if (mysqlCluster.getSpec().getPassiveSwitched().equals(middleware.getAutoSwitch())) {
            changeStatus = true;
            mysqlCluster.getSpec().setPassiveSwitched(!middleware.getAutoSwitch());
        }
        if (changeStatus) {
            try {
                mysqlClusterWrapper.update(middleware.getClusterId(), middleware.getNamespace(), mysqlCluster);
            } catch (IOException e) {
                log.error("集群id:{}，命名空间:{}，mysql集群:{}，开启/关闭自动切换异常", middleware.getClusterId(),
                    middleware.getNamespace(), middleware.getName(), e);
                throw new BusinessException(DictEnum.MYSQL_CLUSTER, middleware.getName(), ErrorMessage.SWITCH_FAILED);
            }
        }
    }

    /**
     * 获取minio
     */
    public Minio getMinio(Middleware middleware) {
        MiddlewareClusterDTO cluster = clusterService.findById(middleware.getClusterId());
        // 获取minio的数据
        Object backupObj = cluster.getStorage().get(BACKUP);
        if (backupObj == null) {
            throw new BusinessException(ErrorMessage.MIDDLEWARE_BACKUP_STORAGE_NOT_EXIST);
        }
        JSONObject backup = JSONObject.parseObject(JSONObject.toJSONString(backupObj));
        if (backup == null || !MINIO.equals(backup.getString(TYPE))) {
            throw new BusinessException(ErrorMessage.MIDDLEWARE_BACKUP_STORAGE_NOT_EXIST);
        }

        return JSONObject.toJavaObject(backup.getJSONObject(STORAGE), Minio.class);
    }

    /**
     * 计算下次备份时间
     */
    public Date calculateNextDate(ScheduleBackup scheduleBackup) {
        try {
            String[] cron = scheduleBackup.getSchedule().split(" ");
            String[] cronWeek = cron[4].split(",");
            List<Date> dateList = new ArrayList<>();
            for (String dayOfWeek : cronWeek) {
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.MINUTE, Integer.parseInt(cron[0]));
                cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(cron[1]));
                cal.set(Calendar.DAY_OF_WEEK, Integer.parseInt(dayOfWeek) + 1);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                Date date = cal.getTime();
                dateList.add(date);
            }
            dateList.sort((d1,d2) -> {
                if (d1.equals(d2)){
                    return 0;
                }
                return d1.before(d2) ? -1 : 1;
            });
            Date now = new Date();
            for (Date date : dateList) {
                if (now.before(date)) {
                    return date;
                }
            }
            return DateUtils.addInteger(dateList.get(0), Calendar.DATE, 7);
        } catch (Exception e) {
            log.error("定时备份{} ,计算下次备份时间失败", scheduleBackup.getName());
            return null;
        }
    }

    @Override
    public void switchDisasterRecovery(String clusterId, String namespace, String middlewareName) throws Exception {
        Middleware middleware = middlewareService.detail(clusterId, namespace, middlewareName, MiddlewareTypeEnum.MYSQL.getType());
        middleware.setClusterId(clusterId);
        middleware.setChartName(MiddlewareTypeEnum.MYSQL.getType());
        MysqlDTO mysqlDTO = middleware.getMysqlDTO();
        if (mysqlDTO != null) {
            Boolean isSource = mysqlDTO.getIsSource();
            if (isSource != null) {
                String relationClusterId = mysqlDTO.getRelationClusterId();
                String relationNamespace = mysqlDTO.getRelationNamespace();
                String relationName = mysqlDTO.getRelationName();

                //获取mysql复制关系，关闭复制关系
                MysqlReplicateCRD mysqlReplicate;
                if (isSource) {
                    mysqlReplicate = mysqlReplicateCRDService.getMysqlReplicate(relationClusterId, relationNamespace, relationName);
                } else {
                    mysqlReplicate = mysqlReplicateCRDService.getMysqlReplicate(clusterId, namespace, middlewareName);
                }
                if (mysqlReplicate != null) {
                    log.info("开始关闭灾备复制,clusterId={}, namespace={}, middlewareName={}", clusterId, namespace, middlewareName);
                    mysqlReplicate.getSpec().setEnable(false);
                    mysqlReplicateCRDService.replaceMysqlReplicate(clusterId, mysqlReplicate);
                    log.info("成功关闭灾备复制");
                } else {
                    log.info("该实例不存在灾备实例");
                }

                try {
                    MiddlewareClusterDTO middlewareClusterDTO = clusterService.findById(clusterId);
                    mysqlDTO.setIsSource(null);
                    mysqlDTO.setOpenDisasterRecoveryMode(false);
                    mysqlDTO.setType("master-slave");
                    update(middleware, middlewareClusterDTO);
                } catch (Exception e) {
                    log.error("实例信息更新失败", e);
                }

                try {
                    Middleware disasterRecovery = middlewareService.detail(relationClusterId, relationNamespace, relationName, MiddlewareTypeEnum.MYSQL.getType());
                    disasterRecovery.setChartName(MiddlewareTypeEnum.MYSQL.getType());
                    disasterRecovery.setClusterId(relationClusterId);
                    MysqlDTO disasterRecoveryMysqlDTO = disasterRecovery.getMysqlDTO();
                    disasterRecoveryMysqlDTO.setIsSource(null);
                    disasterRecoveryMysqlDTO.setOpenDisasterRecoveryMode(false);
                    disasterRecoveryMysqlDTO.setType("master-slave");
                    MiddlewareClusterDTO disasterRecoveryMiddlewareClusterDTO = clusterService.findById(relationClusterId);
                    update(disasterRecovery, disasterRecoveryMiddlewareClusterDTO);
                } catch (Exception e) {
                    log.error("实例信息更新失败", e);
                }

                //灾备切换完成，为灾备实例创建对外可读写服务
                Middleware oldDisasterRecovery;
                if (isSource) {
                    oldDisasterRecovery = middlewareService.detail(relationClusterId, relationNamespace, relationName, MiddlewareTypeEnum.MYSQL.getType());
                    oldDisasterRecovery.setClusterId(relationClusterId);
                    oldDisasterRecovery.setNamespace(relationNamespace);
                    oldDisasterRecovery.setType(MiddlewareTypeEnum.MYSQL.getType());
                } else {
                    oldDisasterRecovery = middlewareService.detail(clusterId, namespace, middlewareName, MiddlewareTypeEnum.MYSQL.getType());
                    oldDisasterRecovery.setClusterId(clusterId);
                    oldDisasterRecovery.setNamespace(namespace);
                    oldDisasterRecovery.setType(MiddlewareTypeEnum.MYSQL.getType());
                }
                tryCreateOpenService(oldDisasterRecovery, ServiceNameConvertUtil.convertMysql(oldDisasterRecovery.getName(), false), true);
            }
        }
    }

    /**
     * 创建灾备实例
     * @param middleware
     */
    public void createDisasterRecoveryMiddleware(Middleware middleware) {
        MysqlDTO mysqlDTO = middleware.getMysqlDTO();
        if (mysqlDTO.getOpenDisasterRecoveryMode() != null && mysqlDTO.getOpenDisasterRecoveryMode() && mysqlDTO.getIsSource()) {
            //1.为实例创建只读对外服务(NodePort)
            tryCreateOpenService(middleware, ServiceNameConvertUtil.convertMysql(middleware.getName(), true), true);
            //2.设置灾备实例信息，创建灾备实例
            //2.1 设置灾备实例信息
            Middleware relationMiddleware = middleware.getRelationMiddleware();
            relationMiddleware.setClusterId(mysqlDTO.getRelationClusterId());
            relationMiddleware.setNamespace(mysqlDTO.getRelationNamespace());
            relationMiddleware.setName(mysqlDTO.getRelationName());
            relationMiddleware.setAliasName(mysqlDTO.getRelationAliasName());

            //2.2 给灾备实例设置源实例信息
            MysqlDTO sourceDto = new MysqlDTO();
            sourceDto.setRelationClusterId(middleware.getClusterId());
            sourceDto.setRelationNamespace(middleware.getNamespace());
            sourceDto.setRelationName(middleware.getName());
            sourceDto.setRelationAliasName(middleware.getAliasName());
            sourceDto.setReplicaCount(middleware.getMysqlDTO().getReplicaCount());
            sourceDto.setOpenDisasterRecoveryMode(true);
            sourceDto.setIsSource(false);
            sourceDto.setType("slave-slave");
            relationMiddleware.setMysqlDTO(sourceDto);

            BaseOperator operator = middlewareService.getOperator(BaseOperator.class, BaseOperator.class, relationMiddleware);
            MiddlewareClusterDTO cluster = clusterService.findByIdAndCheckRegistry(relationMiddleware.getClusterId());
            operator.createPreCheck(relationMiddleware, cluster);
            this.create(relationMiddleware, cluster);
            //3.异步创建关联关系
            this.createMysqlReplicate(middleware, relationMiddleware);
        }
    }

    /**
     * 创建源实例和灾备实例的关联关系
     * @param original
     */
    public void createMysqlReplicate(Middleware original, Middleware disasterRecovery) {
        Middleware middleware = middlewareService.detail(original.getClusterId(), original.getNamespace(), original.getName(), original.getType());
        List<IngressDTO> ingressDTOS = ingressService.get(original.getClusterId(), middleware.getNamespace(),
                middleware.getType(), middleware.getName());
        log.info("准备创建MysqlReplicate,middleware={},ingressDTOS={}", middleware, ingressDTOS);
        if (!CollectionUtils.isEmpty(ingressDTOS)) {
            List<IngressDTO> readonlyIngressDTOList = ingressDTOS.stream().filter(ingressDTO -> (
                    ingressDTO.getName().contains("readonly") && ingressDTO.getExposeType().equals(MIDDLEWARE_EXPOSE_NODEPORT))
            ).collect(Collectors.toList());
            if (!CollectionUtils.isEmpty(readonlyIngressDTOList)) {
                IngressDTO ingressDTO = readonlyIngressDTOList.get(0);
                List<ServiceDTO> serviceList = ingressDTO.getServiceList();
                if (!CollectionUtils.isEmpty(serviceList)) {
                    ServiceDTO serviceDTO = serviceList.get(0);
                    MysqlReplicateSpec spec = new MysqlReplicateSpec(true, disasterRecovery.getName(),
                            ingressDTO.getExposeIP(), Integer.parseInt(serviceDTO.getExposePort()), "root", middleware.getPassword());

                    MysqlReplicateCRD mysqlReplicateCRD = new MysqlReplicateCRD();
                    ObjectMeta metaData = new ObjectMeta();
                    metaData.setName(disasterRecovery.getName());
                    metaData.setNamespace(disasterRecovery.getNamespace());
                    Map<String, String> labels = new HashMap<>();
                    labels.put("operatorname", "mysql-operator");
                    metaData.setLabels(labels);

                    mysqlReplicateCRD.setSpec(spec);
                    mysqlReplicateCRD.setMetadata(metaData);
                    mysqlReplicateCRD.setKind("MysqlReplicate");

                    try {
                        log.info("创建mysql实例 {} 和 {} 的关联关系MysqlReplicate", original.getName(), middleware.getName());
                        mysqlReplicateCRDService.createMysqlReplicate(disasterRecovery.getClusterId(), mysqlReplicateCRD);
                        log.info("MysqlReplicate创建成功");
                    } catch (IOException e) {
                        log.error("MysqlReplicate创建失败", e);
                        e.printStackTrace();
                    }
                }
            }
        } else {
            log.info("未找到只读服务，无法创建MysqlReplicate");
        }
    }

    /**
     * 删除灾备关联关系和关联信息
     * @param middleware
     */
    public void deleteDisasterRecoveryInfo(Middleware middleware) {
        Middleware detail = middlewareService.detail(middleware.getClusterId(), middleware.getNamespace(), middleware.getName(), middleware.getType());
        if (detail != null && detail.getMysqlDTO() != null) {
            MysqlDTO mysqlDTO = detail.getMysqlDTO();
            if (mysqlDTO.getIsSource() != null) {
                //将关联实例中存储的当前实例的信息置空
                String relationClusterId = mysqlDTO.getRelationClusterId();
                String relationNamespace = mysqlDTO.getRelationNamespace();
                String relationName = mysqlDTO.getRelationName();
                Middleware relation = null;
                try {
                    relation = middlewareService.detail(relationClusterId, relationNamespace, relationName, middleware.getType());
                    relation.setChartName(detail.getChartName());
                    MiddlewareClusterDTO cluster = clusterService.findById(relationClusterId);
                    StringBuilder str = new StringBuilder();
                    str.append(String.format("%s.%s=%s,", MysqlConstant.ARGS, MysqlConstant.IS_SOURCE, null));
                    str.append(String.format("%s.%s=%s,", MysqlConstant.ARGS, MysqlConstant.RELATION_CLUSTER_ID, null));
                    str.append(String.format("%s.%s=%s,", MysqlConstant.ARGS, MysqlConstant.RELATION_NAMESPACE, null));
                    str.append(String.format("%s.%s=%s,", MysqlConstant.ARGS, MysqlConstant.RELATION_NAME, null));
                    str.append(String.format("%s.%s=%s", MysqlConstant.ARGS, MysqlConstant.RELATION_ALIAS_NAME, null));
                    helmChartService.upgrade(relation, str.toString(), cluster);
                } catch (Exception e) {
                    log.error("更新关联实例信息出错了", e);
                }
            }
            // 删除灾备关联关系
            try {
                mysqlReplicateCRDService.deleteMysqlReplicate(middleware.getClusterId(), middleware.getNamespace(), middleware.getName());
                log.info("mysql灾备关联关系删除成功");
            } catch (Exception e) {
                log.error("mysql灾备关联关系删除失败", e);
            }
        }
    }

}
