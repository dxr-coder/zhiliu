package com.smartflow.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.common.BusinessException;
import com.smartflow.pojo.entity.Asset;
import com.smartflow.pojo.entity.AssetFlow;
import com.smartflow.pojo.entity.OffboardChecklist;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.mapper.AssetFlowMapper;
import com.smartflow.server.mapper.AssetMapper;
import com.smartflow.server.mapper.OffboardChecklistMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class AssetService {

    /** 合法迁移 from -> to */
    static final Map<String, List<String>> ASSET_TRANSITIONS = Map.of(
            "stock", List.of("assigned", "dispose"),
            "assigned", List.of("stock", "repair", "lost"),
            "repair", List.of("stock", "assigned", "dispose"),
            "lost", List.of("lost_pending_compensate"),
            "lost_pending_compensate", List.of("stock", "dispose"),
            "dispose", List.of("disposed"),
            "disposed", List.of()
    );

    static boolean canMove(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        return ASSET_TRANSITIONS.getOrDefault(from, List.of()).contains(to);
    }

    private final AssetMapper assetMapper;
    private final AssetFlowMapper flowMapper;
    private final OffboardChecklistMapper offboardMapper;
    private final NoticeService noticeService;
    private final AuditService auditService;

    public AssetService(AssetMapper assetMapper,
                        AssetFlowMapper flowMapper,
                        OffboardChecklistMapper offboardMapper,
                        NoticeService noticeService,
                        AuditService auditService) {
        this.assetMapper = assetMapper;
        this.flowMapper = flowMapper;
        this.offboardMapper = offboardMapper;
        this.noticeService = noticeService;
        this.auditService = auditService;
    }

    public List<Asset> list(String status) {
        return assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(status != null && !status.isBlank(), Asset::getStatus, status)
                .orderByAsc(Asset::getSn));
    }

    @Transactional
    public Asset stockIn(LoginUser user, Asset body) {
        if (body.getSn() == null || body.getSn().isBlank()) {
            throw new BusinessException("SN 必填");
        }
        Long dup = assetMapper.selectCount(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getSn, body.getSn()));
        if (dup != null && dup > 0) {
            throw new BusinessException(409, "SN 已存在: " + body.getSn());
        }
        body.setStatus("stock");
        body.setHolderId(null);
        body.setCreatedAt(LocalDateTime.now());
        assetMapper.insert(body);
        flow(body.getId(), "in_stock", null, null, user.uid(), "入库");
        auditService.record(user.uid(), "asset.in_stock", "asset", body.getSn(), null, "stock", null, null);
        return body;
    }

    /** 领用：stock -> assigned。 */
    @Transactional
    public Asset apply(LoginUser user, Long assetId, Long holderId) {
        Asset a = load(assetId);
        if (!canMove(a.getStatus(), "assigned")) {
            throw new BusinessException(409, "当前状态不可领用: " + a.getStatus());
        }
        // 员工只能给自己领；admin/it 可指定
        Long target = (user.isAdmin() || user.hasRole("agent")) && holderId != null
                ? holderId : user.uid();
        String from = a.getStatus();
        a.setStatus("assigned");
        a.setHolderId(target);
        a.setDeptId(user.deptId());
        assetMapper.updateById(a);
        flow(a.getId(), "assign", null, target, user.uid(), "领用");
        noticeService.push(target, "ticket", "资产已分配 " + a.getSn(), a.getName(), "asset", a.getId());
        auditService.record(user.uid(), "asset.assign", "asset", a.getSn(), from, "assigned", null, null);
        return a;
    }

    /** 归还：assigned -> stock。 */
    @Transactional
    public Asset returnAsset(LoginUser user, Long assetId) {
        Asset a = load(assetId);
        boolean mine = a.getHolderId() != null && a.getHolderId().equals(user.uid());
        if (!mine && !user.isAdmin() && !user.hasRole("agent")) {
            throw new BusinessException(403, "仅持有人/管理员可归还");
        }
        if (!canMove(a.getStatus(), "stock")) {
            throw new BusinessException(409, "当前状态不可归还: " + a.getStatus());
        }
        String from = a.getStatus();
        Long prev = a.getHolderId();
        a.setStatus("stock");
        a.setHolderId(null);
        assetMapper.updateById(a);
        flow(a.getId(), "return", prev, null, user.uid(), "归还");
        auditService.record(user.uid(), "asset.return", "asset", a.getSn(), from, "stock", null, null);
        return a;
    }

    /** 维修：assigned/stock -> repair。 */
    @Transactional
    public Asset repair(LoginUser user, Long assetId, String reason) {
        Asset a = load(assetId);
        if (!canMove(a.getStatus(), "repair")) {
            throw new BusinessException(409, "当前状态不可报修: " + a.getStatus());
        }
        String from = a.getStatus();
        a.setStatus("repair");
        assetMapper.updateById(a);
        flow(a.getId(), "repair", a.getHolderId(), null, user.uid(), reason);
        auditService.record(user.uid(), "asset.repair", "asset", a.getSn(), from, "repair", null, null);
        return a;
    }

    /** 丢失：assigned -> lost ->（赔偿流程）-> lost_pending_compensate。 */
    @Transactional
    public Asset lost(LoginUser user, Long assetId, String reason) {
        Asset a = load(assetId);
        if (!"assigned".equals(a.getStatus()) && !"repair".equals(a.getStatus())) {
            throw new BusinessException(409, "仅在用/维修中可报丢失");
        }
        String from = a.getStatus();
        a.setStatus("lost");
        assetMapper.updateById(a);
        flow(a.getId(), "lost", a.getHolderId(), null, user.uid(), reason);
        noticeService.push(a.getHolderId(), "ticket",
                "资产报丢失 " + a.getSn(), reason, "asset", a.getId());
        auditService.record(user.uid(), "asset.lost", "asset", a.getSn(), from, "lost", null, null);
        return a;
    }

    /** 赔偿确认：lost -> lost_pending_compensate（进审批可后补，B6 直接推进）。 */
    @Transactional
    public Asset compensate(LoginUser user, Long assetId) {
        if (!user.hasRole("it_admin") && !user.isAdmin() && !user.hasRole("finance")) {
            throw new BusinessException(403, "仅 it/finance/admin 确认赔偿");
        }
        Asset a = load(assetId);
        if (!"lost".equals(a.getStatus())) {
            throw new BusinessException(409, "仅 lost 可进赔偿");
        }
        a.setStatus("lost_pending_compensate");
        assetMapper.updateById(a);
        flow(a.getId(), "compensate_pending", a.getHolderId(), null, user.uid(), "赔偿流程");
        auditService.record(user.uid(), "asset.compensate", "asset", a.getSn(),
                "lost", "lost_pending_compensate", null, null);
        return a;
    }

    /** 报废：stock/repair/lost_pending -> dispose -> disposed。 */
    @Transactional
    public Asset dispose(LoginUser user, Long assetId, String reason) {
        if (!user.hasRole("it_admin") && !user.isAdmin()) {
            throw new BusinessException(403, "仅 it_admin/admin 报废");
        }
        Asset a = load(assetId);
        if (!canMove(a.getStatus(), "dispose")) {
            throw new BusinessException(409, "当前状态不可报废: " + a.getStatus());
        }
        String from = a.getStatus();
        a.setStatus("dispose");
        assetMapper.updateById(a);
        flow(a.getId(), "dispose", a.getHolderId(), null, user.uid(), reason);
        auditService.record(user.uid(), "asset.dispose", "asset", a.getSn(), from, "dispose", null, null);
        // 立即终态 disposed（B6 简化：无二次确认）
        a.setStatus("disposed");
        assetMapper.updateById(a);
        flow(a.getId(), "disposed", null, null, user.uid(), "报废完成");
        return a;
    }

    // ── 离职 checklist（五门槛）──

    @Transactional
    public OffboardChecklist openOffboard(Long userId) {
        Long open = offboardMapper.selectCount(new LambdaQueryWrapper<OffboardChecklist>()
                .eq(OffboardChecklist::getUserId, userId)
                .eq(OffboardChecklist::getStatus, "open"));
        if (open != null && open > 0) {
            throw new BusinessException(409, "已有进行中的离职单");
        }
        OffboardChecklist c = new OffboardChecklist();
        c.setUserId(userId);
        c.setStatus("open");
        c.setAssetsReturned(false);
        c.setAccountsDisabled(false);
        c.setPermissionsRevoked(false);
        c.setBadgeReturned(false);
        c.setLoanCleared(false);
        c.setCreatedAt(LocalDateTime.now());
        offboardMapper.insert(c);
        return c;
    }

    
    @Transactional
    public OffboardChecklist tickOffboardItem(LoginUser user, Long checklistId, String item, boolean done) {
        OffboardChecklist c = offboardMapper.selectById(checklistId);
        if (c == null || !"open".equals(c.getStatus())) {
            throw new BusinessException(404, "离职单不存在或已结束");
        }
        switch (item) {
            case "assets" -> {
                Long held = assetMapper.selectCount(new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getHolderId, c.getUserId())
                        .eq(Asset::getStatus, "assigned"));
                if (done && held != null && held > 0) {
                    throw new BusinessException(409, "名下仍有 " + held + " 台在用资产");
                }
                c.setAssetsReturned(done);
            }
            case "accounts" -> c.setAccountsDisabled(done);
            case "permissions" -> c.setPermissionsRevoked(done);
            case "badge" -> c.setBadgeReturned(done);
            case "loan" -> c.setLoanCleared(done);
            default -> throw new BusinessException(400, "未知清单项: " + item);
        }
        c.setUpdatedAt(LocalDateTime.now());
        offboardMapper.updateById(c);
        auditService.record(user.uid(), "offboard.tick", "offboard",
                c.getId() + ":" + item, null, String.valueOf(done), null, null);
        return c;
    }

    
    @Transactional
    public OffboardChecklist completeOffboard(LoginUser user, Long checklistId, Boolean forceAll) {
        OffboardChecklist c = offboardMapper.selectById(checklistId);
        if (c == null || !"open".equals(c.getStatus())) {
            throw new BusinessException(404, "离职单不存在或已结束");
        }
        // 兼容旧调用：forceAccounts 单参 -> 视为 accounts；新调用 forceAll 一次勾五项
        if (Boolean.TRUE.equals(forceAll)) {
            c.setAccountsDisabled(true);
            c.setPermissionsRevoked(true);
            c.setBadgeReturned(true);
            c.setLoanCleared(true);
        }
        Long held = assetMapper.selectCount(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getHolderId, c.getUserId())
                .eq(Asset::getStatus, "assigned"));
        if (held != null && held > 0) {
            c.setAssetsReturned(false);
            throw new BusinessException(409, "名下仍有 " + held + " 台在用资产，须先归还/转移");
        }
        c.setAssetsReturned(true);
        if (!Boolean.TRUE.equals(c.getAccountsDisabled())) {
            throw new BusinessException(400, "须勾选：账号已禁用");
        }
        if (!Boolean.TRUE.equals(c.getPermissionsRevoked())) {
            throw new BusinessException(400, "须勾选：权限已回收");
        }
        if (!Boolean.TRUE.equals(c.getBadgeReturned())) {
            throw new BusinessException(400, "须勾选：门禁卡已归还");
        }
        if (!Boolean.TRUE.equals(c.getLoanCleared())) {
            throw new BusinessException(400, "须勾选：借款已核销");
        }
        c.setStatus("done");
        c.setUpdatedAt(LocalDateTime.now());
        offboardMapper.updateById(c);
        auditService.record(user.uid(), "offboard.done", "offboard",
                String.valueOf(c.getId()), "open", "done", null, null);
        return c;
    }

    public List<OffboardChecklist> listOffboard() {
        return offboardMapper.selectList(new LambdaQueryWrapper<OffboardChecklist>()
                .orderByDesc(OffboardChecklist::getCreatedAt));
    }

    public List<AssetFlow> flows(Long assetId) {
        return flowMapper.selectList(new LambdaQueryWrapper<AssetFlow>()
                .eq(AssetFlow::getAssetId, assetId)
                .orderByAsc(AssetFlow::getCreatedAt));
    }

    private Asset load(Long id) {
        Asset a = assetMapper.selectById(id);
        if (a == null) {
            throw new BusinessException(404, "资产不存在");
        }
        return a;
    }

    private void flow(Long assetId, String action, Long from, Long to, Long actor, String reason) {
        AssetFlow f = new AssetFlow();
        f.setAssetId(assetId);
        f.setAction(action);
        f.setFromHolder(from);
        f.setToHolder(to);
        f.setActorId(actor);
        f.setReason(reason);
        f.setCreatedAt(LocalDateTime.now());
        flowMapper.insert(f);
    }
}
