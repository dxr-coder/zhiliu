package com.smartflow.server.service;

import com.smartflow.pojo.entity.Asset;
import com.smartflow.pojo.entity.Expense;
import com.smartflow.pojo.entity.Ticket;
import com.smartflow.pojo.entity.User;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Service
public class ExportService {

    public byte[] exportExpenses(List<Expense> rows) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("报销");
            String[] headers = {"ID", "单号", "类型", "金额", "状态", "成本中心", "创建时间"};
            writeHeader(sheet, headers);
            int r = 1;
            for (Expense e : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(e.getId() == null ? "" : String.valueOf(e.getId()));
                row.createCell(1).setCellValue(nvl(e.getExpenseNo()));
                row.createCell(2).setCellValue(nvl(e.getType()));
                row.createCell(3).setCellValue(e.getAmountTotal() == null ? 0 : e.getAmountTotal().doubleValue());
                row.createCell(4).setCellValue(nvl(e.getStatus()));
                row.createCell(5).setCellValue(nvl(e.getCostCenter()));
                row.createCell(6).setCellValue(e.getCreatedAt() == null ? "" : e.getCreatedAt().toString());
            }
            autoSize(sheet, headers.length);
            wb.write(out);
            return out.toByteArray();
        }
    }

    public byte[] exportTickets(List<Ticket> rows) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("工单");
            String[] headers = {"ID", "单号", "标题", "分类", "优先级", "状态", "处理人", "SLA截止", "创建时间"};
            writeHeader(sheet, headers);
            int r = 1;
            for (Ticket t : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(t.getId() == null ? "" : String.valueOf(t.getId()));
                row.createCell(1).setCellValue(nvl(t.getTicketNo()));
                row.createCell(2).setCellValue(nvl(t.getTitle()));
                row.createCell(3).setCellValue(nvl(t.getCategory()));
                row.createCell(4).setCellValue(nvl(t.getPriority()));
                row.createCell(5).setCellValue(nvl(t.getStatus()));
                row.createCell(6).setCellValue(t.getAssigneeId() == null ? "" : String.valueOf(t.getAssigneeId()));
                row.createCell(7).setCellValue(t.getSlaDeadline() == null ? "" : t.getSlaDeadline().toString());
                row.createCell(8).setCellValue(t.getCreatedAt() == null ? "" : t.getCreatedAt().toString());
            }
            autoSize(sheet, headers.length);
            wb.write(out);
            return out.toByteArray();
        }
    }

    public byte[] exportAssets(List<Asset> rows) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("资产");
            String[] headers = {"ID", "SN", "名称", "状态", "持有人", "部门", "位置", "购置日", "保修至"};
            writeHeader(sheet, headers);
            int r = 1;
            for (Asset a : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(a.getId() == null ? "" : String.valueOf(a.getId()));
                row.createCell(1).setCellValue(nvl(a.getSn()));
                row.createCell(2).setCellValue(nvl(a.getName()));
                row.createCell(3).setCellValue(nvl(a.getStatus()));
                row.createCell(4).setCellValue(a.getHolderId() == null ? "" : String.valueOf(a.getHolderId()));
                row.createCell(5).setCellValue(a.getDeptId() == null ? "" : String.valueOf(a.getDeptId()));
                row.createCell(6).setCellValue(nvl(a.getLocation()));
                row.createCell(7).setCellValue(a.getBuyDate() == null ? "" : a.getBuyDate().toString());
                row.createCell(8).setCellValue(a.getWarrantyEnd() == null ? "" : a.getWarrantyEnd().toString());
            }
            autoSize(sheet, headers.length);
            wb.write(out);
            return out.toByteArray();
        }
    }

    public byte[] exportUsers(List<User> rows) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("用户");
            String[] headers = {"ID", "工号", "用户名", "姓名", "部门", "状态", "创建时间"};
            writeHeader(sheet, headers);
            int r = 1;
            for (User u : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(u.getId() == null ? "" : String.valueOf(u.getId()));
                row.createCell(1).setCellValue(nvl(u.getEmpNo()));
                row.createCell(2).setCellValue(nvl(u.getUsername()));
                row.createCell(3).setCellValue(nvl(u.getRealName()));
                row.createCell(4).setCellValue(u.getDeptId() == null ? "" : String.valueOf(u.getDeptId()));
                row.createCell(5).setCellValue(nvl(u.getStatus()));
                row.createCell(6).setCellValue(u.getCreatedAt() == null ? "" : u.getCreatedAt().toString());
            }
            autoSize(sheet, headers.length);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void writeHeader(Sheet sheet, String[] headers) {
        Row h = sheet.createRow(0);
        CellStyle style = sheet.getWorkbook().createCellStyle();
        Font font = sheet.getWorkbook().createFont();
        font.setBold(true);
        style.setFont(font);
        for (int i = 0; i < headers.length; i++) {
            Cell c = h.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(style);
        }
        sheet.createFreezePane(0, 1);
    }

    private void autoSize(Sheet sheet, int cols) {
        for (int i = 0; i < cols; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }
}
