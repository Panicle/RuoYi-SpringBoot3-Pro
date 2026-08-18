package com.ruoyi.biz.service;

import com.ruoyi.biz.domain.BudgetSplit;
import com.ruoyi.biz.domain.Expense;
import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.ProjectMember;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.UUID;

/**
 * 对话精灵文档生成（generate_expense_report / generate_project_doc 两工具的支撑）
 *
 * <p>数据一律走各 Service 的 scoped/角色通道（selectProjectById 闸门先行，无权即抛），
 * 文档内容全部由代码模板写入 — LLM 只负责意图理解与传参，不生成任何数字。</p>
 *
 * <p>产物落 {@link RuoYiConfig#getDownloadPath()}/chat/ 目录，文件名 =
 * 前缀_课题编号(仅留字母数字-_)_UUID前8位.扩展名，返回 /profile/download/chat/xx 相对路径
 * （/profile/** 静态映射，前端 ChatWidget 拼 baseAPI 渲染成下载链接）。</p>
 *
 * @author kys
 * @date 2026-08-17
 */
@Service
@RequiredArgsConstructor
public class ChatReportService {

    private final IProjectService projectService;
    private final IBudgetService budgetService;
    private final IExpenseService expenseService;

    /**
     * 课题经费执行报告（Excel）：sheet1 预算执行（科目/预算/已用/余额+合计），sheet2 支出明细。
     *
     * @return /profile/download/chat/xx.xlsx 相对下载路径
     */
    public String generateExpenseExcel(Long projectId) {
        if (projectId == null) {
            throw new ServiceException("projectId 不能为空");
        }
        // scoped 闸门（无权访问 → 抛异常，researcher 仅本人相关课题）
        Project project = projectService.selectProjectById(projectId);
        List<BudgetSplit> splits = budgetService.selectBudgetList(projectId);
        Expense query = new Expense();
        query.setProjectId(projectId);
        List<Expense> expenses = expenseService.selectExpenseList(query);

        try (Workbook wb = new XSSFWorkbook()) {
            CellStyle header = headerStyle(wb);
            CellStyle amount = amountStyle(wb);

            // sheet1 预算执行
            Sheet s1 = wb.createSheet("预算执行");
            s1.setDefaultColumnWidth(16);
            writeRow(s1, 0, header, "预算科目", "预算金额", "已用金额", "余额");
            int r = 1;
            BigDecimal tb = BigDecimal.ZERO, tu = BigDecimal.ZERO, tl = BigDecimal.ZERO;
            for (BudgetSplit sp : splits) {
                Row row = s1.createRow(r++);
                row.createCell(0).setCellValue(safe(sp.getCategory()));
                numCell(row, 1, sp.getBudgetAmount(), amount);
                numCell(row, 2, sp.getUsedAmount(), amount);
                numCell(row, 3, sp.getBalance(), amount);
                tb = tb.add(nz(sp.getBudgetAmount()));
                tu = tu.add(nz(sp.getUsedAmount()));
                tl = tl.add(nz(sp.getBalance()));
            }
            Row total = s1.createRow(r);
            total.createCell(0).setCellValue("合计");
            numCell(total, 1, tb, amount);
            numCell(total, 2, tu, amount);
            numCell(total, 3, tl, amount);

            // sheet2 支出明细
            Sheet s2 = wb.createSheet("支出明细");
            s2.setDefaultColumnWidth(16);
            writeRow(s2, 0, header, "记账日期", "预算科目", "金额", "税率", "摘要", "状态");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            int r2 = 1;
            for (Expense e : expenses) {
                Row row = s2.createRow(r2++);
                row.createCell(0).setCellValue(e.getExpenseDate() == null ? "" : sdf.format(e.getExpenseDate()));
                row.createCell(1).setCellValue(safe(e.getCategoryLabel() != null ? e.getCategoryLabel() : e.getCategory()));
                numCell(row, 2, e.getAmount(), amount);
                row.createCell(3).setCellValue(safe(e.getTaxRate()));
                row.createCell(4).setCellValue(safe(e.getDescription()));
                row.createCell(5).setCellValue(safe(e.getStatus()));
            }

            return saveToChatDir(wb::write, "expense", project.getProjectNo(), ".xlsx");
        } catch (IOException e) {
            throw new ServiceException("经费报告生成失败：" + e.getMessage());
        }
    }

    /**
     * 课题综合档案（Word docx）：基本信息 + 成员名单 + 预算科目表。
     *
     * @return /profile/download/chat/xx.docx 相对下载路径
     */
    public String generateProjectDocx(Long projectId) {
        if (projectId == null) {
            throw new ServiceException("projectId 不能为空");
        }
        Project p = projectService.selectProjectById(projectId);
        List<ProjectMember> members = projectService.selectMemberList(projectId);
        List<BudgetSplit> splits = budgetService.selectBudgetList(projectId);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        try (XWPFDocument doc = new XWPFDocument()) {
            // 标题
            XWPFParagraph title = doc.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun tr = title.createRun();
            tr.setText("课题档案：" + safe(p.getProjectName()));
            tr.setBold(true);
            tr.setFontSize(18);

            // 基本信息表（两列 label/value）
            heading(doc, "一、基本信息");
            XWPFTable info = doc.createTable();
            infoRow(info, true, "课题编号", safe(p.getProjectNo()), "课题名称", safe(p.getProjectName()));
            infoRow(info, false, "课题类型", safe(p.getProjectTypeLabel() != null ? p.getProjectTypeLabel() : p.getProjectType()),
                    "状态", safe(p.getStatusLabel() != null ? p.getStatusLabel() : p.getStatus()));
            infoRow(info, false, "组长", safe(p.getLeaderName()), "承担部门", safe(p.getDeptName()));
            infoRow(info, false, "起始日期", p.getStartDate() == null ? "" : sdf.format(p.getStartDate()),
                    "结束日期", p.getEndDate() == null ? "" : sdf.format(p.getEndDate()));
            infoRow(info, false, "预算总额（元）", money(p.getBudgetTotal()), "预算余额（元）", money(p.getBudgetBalance()));

            // 成员名单
            heading(doc, "二、课题成员（" + (members == null ? 0 : members.size()) + " 人）");
            XWPFTable mt = doc.createTable();
            XWPFTableRow mh = mt.getRow(0);
            mh.getCell(0).setText("姓名");
            mh.addNewTableCell().setText("登录账号");
            mh.addNewTableCell().setText("部门");
            mh.addNewTableCell().setText("角色");
            if (members != null) {
                for (ProjectMember m : members) {
                    XWPFTableRow row = mt.createRow();
                    row.getCell(0).setText(safe(m.getNickName()));
                    row.getCell(1).setText(safe(m.getUserName()));
                    row.getCell(2).setText(safe(m.getDeptName()));
                    row.getCell(3).setText("HOST".equals(m.getRole()) ? "组长" : "成员");
                }
            }

            // 预算科目
            heading(doc, "三、预算科目");
            XWPFTable bt = doc.createTable();
            XWPFTableRow bh = bt.getRow(0);
            bh.getCell(0).setText("预算科目");
            bh.addNewTableCell().setText("预算金额（元）");
            bh.addNewTableCell().setText("已用金额（元）");
            bh.addNewTableCell().setText("余额（元）");
            for (BudgetSplit sp : splits) {
                XWPFTableRow row = bt.createRow();
                row.getCell(0).setText(safe(sp.getCategory()));
                row.getCell(1).setText(money(sp.getBudgetAmount()));
                row.getCell(2).setText(money(sp.getUsedAmount()));
                row.getCell(3).setText(money(sp.getBalance()));
            }

            return saveToChatDir(doc::write, "project", p.getProjectNo(), ".docx");
        } catch (IOException e) {
            throw new ServiceException("课题档案生成失败：" + e.getMessage());
        }
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /** 输出到 download/chat/ 目录并返回 /profile 相对路径；文件名仅留 ASCII 安全字符 */
    private String saveToChatDir(Writer writer, String prefix, String projectNo, String ext) throws IOException {
        String no = projectNo == null ? "" : projectNo.replaceAll("[^A-Za-z0-9_-]", "");
        String name = prefix + (no.isEmpty() ? "" : "_" + no) + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ext;
        File dir = new File(RuoYiConfig.getDownloadPath() + "chat");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new ServiceException("下载目录创建失败：" + dir.getAbsolutePath());
        }
        File out = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            writer.write(fos);
        }
        return Constants.RESOURCE_PREFIX + "/download/chat/" + name;
    }

    /** POI Workbook/XWPFDocument 共用的写出函数式接口 */
    @FunctionalInterface
    private interface Writer {
        void write(java.io.OutputStream os) throws IOException;
    }

    private static void writeRow(Sheet sheet, int rowIdx, CellStyle style, String... texts) {
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < texts.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(texts[i]);
            c.setCellStyle(style);
        }
    }

    private static void numCell(Row row, int col, BigDecimal v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(nz(v).doubleValue());
        c.setCellStyle(style);
    }

    private CellStyle headerStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private CellStyle amountStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return style;
    }

    private static void heading(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(200);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(true);
        r.setFontSize(13);
    }

    /** 基本信息表一行（label1/value1/label2/value2）；first=true 时用 createTable 自带的第一行 */
    private static void infoRow(XWPFTable table, boolean first, String l1, String v1, String l2, String v2) {
        XWPFTableRow row = first ? table.getRow(0) : table.createRow();
        if (first) {
            row.getCell(0).setText(l1);
            row.addNewTableCell().setText(v1);
            row.addNewTableCell().setText(l2);
            row.addNewTableCell().setText(v2);
        } else {
            row.getCell(0).setText(l1);
            row.getCell(1).setText(v1);
            row.getCell(2).setText(l2);
            row.getCell(3).setText(v2);
        }
    }

    private static String money(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
