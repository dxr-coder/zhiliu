package com.smartflow.server.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartflow.common.BusinessException;
import com.smartflow.pojo.entity.ApprovalInstance;
import com.smartflow.pojo.entity.ApprovalTask;
import com.smartflow.pojo.entity.ApprovalTemplate;
import com.smartflow.server.mapper.ApprovalInstanceMapper;
import com.smartflow.server.mapper.ApprovalTaskMapper;
import com.smartflow.server.mapper.ApprovalTemplateMapper;
import com.smartflow.server.mapper.RoleMapper;
import com.smartflow.server.mapper.UserMapper;
import com.smartflow.pojo.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ApprovalEngine {

    private static final Logger log = LoggerFactory.getLogger(ApprovalEngine.class);

    private final ApprovalInstanceMapper instanceMapper;
    private final ApprovalTaskMapper taskMapper;
    private final ApprovalTemplateMapper templateMapper;
    private final RoleMapper roleMapper;
    private final UserMapper userMapper;
    private final ObjectMapper om = new ObjectMapper();

    public ApprovalEngine(ApprovalInstanceMapper instanceMapper,
                          ApprovalTaskMapper taskMapper,
                          ApprovalTemplateMapper templateMapper,
                          RoleMapper roleMapper,
                          UserMapper userMapper) {
        this.instanceMapper = instanceMapper;
        this.taskMapper = taskMapper;
        this.templateMapper = templateMapper;
        this.roleMapper = roleMapper;
        this.userMapper = userMapper;
    }

    /**
     * 启动审批。
     * @param bizType leave/expense...
     * @param bizId 业务主键
     * @param starterId 发起人
     * @param stepsJson 链快照（优先）；null 则读模板最新版
     * @param context 用于 skipWhen（如 days）
     */
    @Transactional
    public Long start(String bizType, Long bizId, Long starterId,
                      String stepsJson, Map<String, Object> context) {
        ApprovalTemplate tpl = null;
        String steps = stepsJson;
        int ver = 1;
        Long tplId;
        if (steps == null || steps.isBlank()) {
            tpl = templateMapper.selectOne(new LambdaQueryWrapper<ApprovalTemplate>()
                    .eq(ApprovalTemplate::getBizType, bizType)
                    .eq(ApprovalTemplate::getStatus, "active")
                    .orderByDesc(ApprovalTemplate::getVersion)
                    .last("LIMIT 1"));
            if (tpl == null) {
                throw new BusinessException(409, "无审批模板: " + bizType);
            }
            steps = tpl.getStepsJson();
            ver = tpl.getVersion();
            tplId = tpl.getId();
        } else {
            tplId = 0L;
        }

        List<Map<String, Object>> stepsList;
        try {
            stepsList = om.readValue(steps, new TypeReference<>() {});
        } catch (Exception e) {
            throw new BusinessException(500, "审批链 JSON 解析失败");
        }

        ApprovalInstance inst = new ApprovalInstance();
        inst.setBizType(bizType);
        inst.setBizId(bizId);
        inst.setTemplateId(tplId);
        inst.setTemplateVer(ver);
        inst.setCurrentStep(1);
        inst.setStatus("running");
        inst.setStarterId(starterId);
        inst.setCreatedAt(LocalDateTime.now());
        instanceMapper.insert(inst);

        List<Integer> activeSteps = new ArrayList<>();
        for (Map<String, Object> s : stepsList) {
            int step = ((Number) s.get("step")).intValue();
            String role = String.valueOf(s.get("role"));
            String skipWhen = s.get("skipWhen") == null ? null : String.valueOf(s.get("skipWhen"));
            if (skipWhen != null && shouldSkip(skipWhen, context)) {
                continue;
            }
            activeSteps.add(step);
            // 或签：同 step 给该角色下所有用户建任务（B1 简化：取角色首个有单的用户列表）
            List<Long> approvers = approverIdsOfRole(role, starterId);
            if (approvers.isEmpty()) {
                // 角色无人：跳过该步（可配，B1 先跳过并打日志）
                log.warn("[approval] role {} no approver, skip step {}", role, step);
                continue;
            }
            for (Long aid : approvers) {
                ApprovalTask t = new ApprovalTask();
                t.setInstanceId(inst.getId());
                t.setStep(step);
                t.setApproverId(aid);
                t.setAction("pending");
                taskMapper.insert(t);
            }
        }
        if (activeSteps.isEmpty()) {
            inst.setStatus("approved");
            inst.setFinishedAt(LocalDateTime.now());
            instanceMapper.updateById(inst);
        } else {
            inst.setCurrentStep(activeSteps.get(0));
            instanceMapper.updateById(inst);
        }
        return inst.getId();
    }

    /**
     * 同意一步。
     * @return instance 是否终态 approved
     */
    @Transactional
    public boolean agree(Long instanceId, Long operatorId, String comment) {
        ApprovalInstance inst = loadRunning(instanceId);
        List<ApprovalTask> pendings = taskMapper.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getInstanceId, instanceId)
                .eq(ApprovalTask::getStep, inst.getCurrentStep())
                .eq(ApprovalTask::getAction, "pending"));
        boolean mine = pendings.stream().anyMatch(t -> t.getApproverId().equals(operatorId));
        if (!mine) {
            throw new BusinessException(403, "当前节点无你的待办");
        }
        LocalDateTime now = LocalDateTime.now();
        // 或签：同 step 任一人同意即可，其余 pending 置 skip
        for (ApprovalTask t : pendings) {
            if (t.getApproverId().equals(operatorId)) {
                t.setAction("agree");
                t.setComment(comment);
                t.setActedAt(now);
            } else {
                t.setAction("skip");
                t.setActedAt(now);
            }
            taskMapper.updateById(t);
        }
        return advance(inst);
    }

    /** 驳回到发起人（B1 策略 to_start）。 */
    @Transactional
    public void reject(Long instanceId, Long operatorId, String comment) {
        ApprovalInstance inst = loadRunning(instanceId);
        List<ApprovalTask> pendings = taskMapper.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getInstanceId, instanceId)
                .eq(ApprovalTask::getStep, inst.getCurrentStep())
                .eq(ApprovalTask::getAction, "pending"));
        boolean mine = pendings.stream().anyMatch(t -> t.getApproverId().equals(operatorId));
        if (!mine) {
            throw new BusinessException(403, "当前节点无你的待办");
        }
        LocalDateTime now = LocalDateTime.now();
        for (ApprovalTask t : pendings) {
            if (t.getApproverId().equals(operatorId)) {
                t.setAction("reject");
                t.setComment(comment);
            } else {
                t.setAction("skip");
            }
            t.setActedAt(now);
            taskMapper.updateById(t);
        }
        inst.setStatus("rejected");
        inst.setFinishedAt(now);
        instanceMapper.updateById(inst);
    }

    public List<ApprovalTask> todoOf(Long userId) {
        return taskMapper.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApproverId, userId)
                .eq(ApprovalTask::getAction, "pending"));
    }

    
    @Transactional
    public void transfer(Long instanceId, Long operatorId, Long toUserId, String comment) {
        if (toUserId == null || toUserId.equals(operatorId)) {
            throw new BusinessException(400, "转办目标无效");
        }
        User tUser = userMapper.selectById(toUserId);
        if (tUser == null || !"active".equals(tUser.getStatus())) {
            throw new BusinessException(404, "转办目标用户不存在或已禁用");
        }
        ApprovalInstance inst = loadRunning(instanceId);
        List<ApprovalTask> pendings = taskMapper.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getInstanceId, instanceId)
                .eq(ApprovalTask::getStep, inst.getCurrentStep())
                .eq(ApprovalTask::getAction, "pending"));
        ApprovalTask mine = pendings.stream()
                .filter(t -> t.getApproverId().equals(operatorId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(403, "当前节点无你的待办"));
        // 原任务标 transfer，新建目标任务
        mine.setAction("transfer");
        mine.setComment(comment == null ? "transfer->" + toUserId : comment + " ->" + toUserId);
        mine.setActedAt(LocalDateTime.now());
        taskMapper.updateById(mine);

        ApprovalTask next = new ApprovalTask();
        next.setInstanceId(instanceId);
        next.setStep(inst.getCurrentStep());
        next.setApproverId(toUserId);
        next.setAction("pending");
        taskMapper.insert(next);
        log.info("[approval] transfer {} step{} {} -> {}", instanceId, inst.getCurrentStep(), operatorId, toUserId);
    }

    /**
     * 加签（后置）：在当前节点后插入一个额外同意节点（同 step 或 step+0.5 用 step 整数则并到当前 step 末尾任务）。
     * B1/B4 简化：加签人与当前 step 同 step 建 pending，全部同意才 advance（或签语义下需注意）。
     * 更稳做法：新建 step = currentStep + 1000/10 保证顺序——这里用 step 不变+多人 pending=或签，加签改为独立 step。
     */
    @Transactional
    public void addSign(Long instanceId, Long operatorId, Long toUserId, String comment) {
        if (toUserId == null) {
            throw new BusinessException(400, "加签人必填");
        }
        User target = userMapper.selectById(toUserId);
        if (target == null || !"active".equals(target.getStatus())) {
            throw new BusinessException(404, "加签目标用户不存在或已禁用");
        }
        ApprovalInstance inst = loadRunning(instanceId);
        List<ApprovalTask> pendings = taskMapper.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getInstanceId, instanceId)
                .eq(ApprovalTask::getStep, inst.getCurrentStep())
                .eq(ApprovalTask::getAction, "pending"));
        boolean mine = pendings.stream().anyMatch(t -> t.getApproverId().equals(operatorId));
        if (!mine) {
            throw new BusinessException(403, "当前节点无你的待办");
        }
        // 加签=并入当前节点（同 step 多人或签）：任一 agree 即过，与 B1 或签语义一致
        ApprovalTask t = new ApprovalTask();
        t.setInstanceId(instanceId);
        t.setStep(inst.getCurrentStep());
        t.setApproverId(toUserId);
        t.setAction("pending");
        t.setComment(comment == null ? "add_sign" : "add_sign: " + comment);
        taskMapper.insert(t);
        log.info("[approval] add_sign {} step{} by {} -> {}", instanceId, inst.getCurrentStep(), operatorId, toUserId);
    }

    private boolean advance(ApprovalInstance inst) {
        List<ApprovalTask> rest = taskMapper.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getInstanceId, inst.getId())
                .eq(ApprovalTask::getAction, "pending"));
        if (rest.isEmpty()) {
            inst.setStatus("approved");
            inst.setFinishedAt(LocalDateTime.now());
            instanceMapper.updateById(inst);
            return true;
        }
        int nextStep = rest.stream().mapToInt(ApprovalTask::getStep).min().orElse(inst.getCurrentStep());
        inst.setCurrentStep(nextStep);
        instanceMapper.updateById(inst);
        return false;
    }

    private ApprovalInstance loadRunning(Long instanceId) {
        ApprovalInstance inst = instanceMapper.selectById(instanceId);
        if (inst == null) {
            throw new BusinessException(404, "审批实例不存在");
        }
        if (!"running".equals(inst.getStatus())) {
            throw new BusinessException(409, "审批已结束: " + inst.getStatus());
        }
        return inst;
    }

    private List<Long> approverIdsOfRole(String roleCode, Long starterId) {
        List<Long> ids = roleMapper.selectUserIdsByRoleCode(roleCode);
        return ids == null ? List.of() : ids;
    }

    private boolean shouldSkip(String skipWhen, Map<String, Object> ctx) {
        if (ctx == null || skipWhen == null) {
            return false;
        }
        // 支持 days<=3 形式
        if (skipWhen.startsWith("days<=")) {
            double limit = Double.parseDouble(skipWhen.substring(6));
            Object d = ctx.get("days");
            if (d == null) {
                return false;
            }
            return ((Number) d).doubleValue() <= limit;
        }
        return false;
    }
}
