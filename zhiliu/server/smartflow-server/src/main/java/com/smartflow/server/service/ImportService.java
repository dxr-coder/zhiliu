package com.smartflow.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartflow.common.BusinessException;
import com.smartflow.pojo.entity.Asset;
import com.smartflow.pojo.entity.Expense;
import com.smartflow.pojo.entity.ExpenseItem;
import com.smartflow.pojo.entity.User;
import com.smartflow.server.interceptor.AuthInterceptor.LoginUser;
import com.smartflow.server.mapper.AssetMapper;
import com.smartflow.server.mapper.ExpenseItemMapper;
import com.smartflow.server.mapper.ExpenseMapper;
import com.smartflow.server.mapper.UserMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class ImportService {

    private final AssetMapper assetMapper;
    private final ExpenseMapper expenseMapper;
    private final ExpenseItemMapper itemMapper;
    private final UserMapper userMapper;
    private final AuditService auditService;

    public ImportService(AssetMapper assetMapper,
                         ExpenseMapper expenseMapper,
                         ExpenseItemMapper itemMapper,
                         UserMapper userMapper,
                         AuditService auditService) {
        this.assetMapper = assetMapper;
        this.expenseMapper = expenseMapper;
        this.itemMapper = itemMapper;
        this.userMapper = userMapper;
        this.auditService = auditService;
    }

    public record ImportResult(int created, List<String> errors) {
    }

    /** 资产导入：列 sn,name,location（可选 category）。SN 重复跳过记错误。 */
    @Transactional
    public ImportResult importAssets(LoginUser user, MultipartFile file) {
        if (!user.hasRole("it_admin") && !user.isAdmin() && !user.hasRole("agent")) {
            throw new BusinessException(403, "仅 it/admin 可导入资产");
        }
        List<String> errors = new ArrayList<>();
        int created = 0;
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String sn = str(row.getCell(0));
                String name = str(row.getCell(1));
                String location = str(row.getCell(2));
                if (sn == null || sn.isBlank()) {
                    errors.add("行" + (r + 1) + ": SN 空");
                    continue;
                }
                if (name == null || name.isBlank()) {
                    errors.add("行" + (r + 1) + ": 名称空");
                    continue;
                }
                Long dup = assetMapper.selectCount(new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getSn, sn));
                if (dup != null && dup > 0) {
                    errors.add("行" + (r + 1) + ": SN 已存在 " + sn);
                    continue;
                }
                Asset a = new Asset();
                a.setSn(sn.trim());
                a.setName(name.trim());
                a.setLocation(location);
                a.setStatus("stock");
                assetMapper.insert(a);
                created++;
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Excel 解析失败: " + e.getMessage());
        }
        auditService.record(user.uid(), "asset.import", "asset", "batch",
                null, "created=" + created, null, null);
        return new ImportResult(created, errors);
    }

    /**
     * 报销导入：每行一条明细，列 type,amount,invoiceNo,itemName。
     * 同 type+金额聚成一单（简化：一行一单）。
     */
    @Transactional
    public ImportResult importExpenses(LoginUser user, MultipartFile file) {
        List<String> errors = new ArrayList<>();
        int created = 0;
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String type = str(row.getCell(0));
                String amountStr = str(row.getCell(1));
                String invoiceNo = str(row.getCell(2));
                String itemName = str(row.getCell(3));
                if (type == null || type.isBlank()) {
                    errors.add("行" + (r + 1) + ": type 空");
                    continue;
                }
                BigDecimal amount;
                try {
                    amount = new BigDecimal(amountStr == null ? "" : amountStr.trim());
                } catch (Exception ex) {
                    errors.add("行" + (r + 1) + ": 金额非法 " + amountStr);
                    continue;
                }
                if (amount.signum() <= 0) {
                    errors.add("行" + (r + 1) + ": 金额须>0");
                    continue;
                }
                if (invoiceNo != null && !invoiceNo.isBlank()) {
                    Long c = itemMapper.selectCount(new LambdaQueryWrapper<ExpenseItem>()
                            .eq(ExpenseItem::getInvoiceNo, invoiceNo.trim()));
                    if (c != null && c > 0) {
                        errors.add("行" + (r + 1) + ": 发票号已存在 " + invoiceNo);
                        continue;
                    }
                }
                Expense e = new Expense();
                e.setUserId(user.uid());
                e.setDeptId(user.deptId());
                e.setType(type.trim());
                e.setAmountTotal(amount);
                e.setStatus("draft");
                e.setAiGenerated(false);
                e.setCreatedAt(java.time.LocalDateTime.now());
                expenseMapper.insert(e);

                ExpenseItem it = new ExpenseItem();
                it.setExpenseId(e.getId());
                it.setName(itemName == null || itemName.isBlank() ? type : itemName.trim());
                it.setAmount(amount);
                it.setInvoiceNo(invoiceNo == null || invoiceNo.isBlank() ? null : invoiceNo.trim());
                itemMapper.insert(it);
                created++;
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            throw new BusinessException("Excel 解析失败: " + e.getMessage());
        }
        auditService.record(user.uid(), "expense.import", "expense", "batch",
                null, "created=" + created, null, null);
        return new ImportResult(created, errors);
    }

    /** 用户导入：empNo,username,realName,deptId；username 重复跳过。密码统一占位由 PasswordSeed/首登改。 */
    @Transactional
    public ImportResult importUsers(LoginUser user, MultipartFile file) {
        if (!user.isAdmin() && !user.hasRole("hr")) {
            throw new BusinessException(403, "仅 admin/hr 可导入用户");
        }
        List<String> errors = new ArrayList<>();
        int created = 0;
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String empNo = str(row.getCell(0));
                String username = str(row.getCell(1));
                String realName = str(row.getCell(2));
                String deptStr = str(row.getCell(3));
                if (username == null || username.isBlank()) {
                    errors.add("行" + (r + 1) + ": username 空");
                    continue;
                }
                Long dup = userMapper.selectCount(new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username.trim()));
                if (dup != null && dup > 0) {
                    errors.add("行" + (r + 1) + ": 用户名已存在 " + username);
                    continue;
                }
                User u = new User();
                u.setEmpNo(empNo);
                u.setUsername(username.trim());
                u.setRealName(realName);
                u.setStatus("active");
                if (deptStr != null && !deptStr.isBlank()) {
                    try {
                        u.setDeptId(Long.parseLong(deptStr.trim()));
                    } catch (NumberFormatException ignore) {
                        // dept 可空
                    }
                }
                // 占位 hash，与 schema 一致 {noop}123456，启动 PasswordSeed 或登录前 UPDATE
                u.setPasswordHash("{noop}123456");
                userMapper.insert(u);
                created++;
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Excel 解析失败: " + e.getMessage());
        }
        auditService.record(user.uid(), "user.import", "user", "batch",
                null, "created=" + created, null, null);
        return new ImportResult(created, errors);
    }

    private String str(Cell cell) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            if (d == Math.floor(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return cell.toString();
    }
}
