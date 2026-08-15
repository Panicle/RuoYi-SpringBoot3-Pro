package com.ruoyi.biz.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.biz.domain.Project;
import com.ruoyi.biz.domain.RdLaborAllocation;
import com.ruoyi.biz.domain.RdWorktimeMonthly;
import com.ruoyi.biz.domain.SurchargeRate;
import com.ruoyi.biz.domain.vo.RdAllocSummaryRow;
import com.ruoyi.biz.mapper.RdLaborAllocationMapper;
import com.ruoyi.biz.mapper.RdWorktimeMonthlyMapper;
import com.ruoyi.biz.mapper.SurchargeRateMapper;
import com.ruoyi.biz.service.IProjectService;
import com.ruoyi.biz.service.IRdExportService;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import jakarta.servlet.http.HttpServletResponse;
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
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 研发加计扣除 — Excel 导出 Service 实现（任务卡 Task 4 端点 16-18）
 *
 * <p>三个端点统一走 POI 直写（{@link XSSFWorkbook}）到 {@link HttpServletResponse}，
 * 不走 {@code ExcelUtil}：端点 17 的 10 项附加费列需要动态列；端点 17/18 合计行/总计行
 * 文本与数值混排，ExcelUtil 的 {@code addStatisticsRow} 仅写双精度累加且无文本单元格。
 * 金额一律 2 位 HALF_UP（与算法一致），合计/总计行 BigDecimal 精确求和（不用 double）。</p>
 *
 * <p>数据可见性：所有端点先过 {@code projectService.selectProjectById} scoped 闸门；
 * researcher 角色对端点 17 的他人行 hourlyRate 字段置 null（任务卡 D11）；
 * 端点 18 在 projectIds 为空/空集合时改走 {@code projectService.selectProjectList} 拉全部可见课题。
 * 附加费列头按 {@code surcharge_rate.rate_id} 升序取 {@code rate_name}。</p>
 *
 * @author kys
 * @date 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class RdExportServiceImpl implements IRdExportService {

    /** 角色 key */
    private static final String ROLE_RESEARCHER = "researcher";
    private static final String ROLE_ADMIN = "admin";

    /** 月份格式校验 */
    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    /** 2 位 HALF_UP（合计/总计行与算法口径一致） */
    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** 金额列格式（保留 2 位小数；超长仍按原始精度展示） */
    private static final String AMOUNT_FORMAT = "#,##0.00";

    private final RdWorktimeMonthlyMapper rdWorktimeMonthlyMapper;
    private final RdLaborAllocationMapper rdLaborAllocationMapper;
    private final SurchargeRateMapper surchargeRateMapper;
    private final IProjectService projectService;

    /** 复用 Jackson 单例 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ========================================================
    //  端点 16 — 某课题某月工时统计表
    // ========================================================

    @Override
    public void exportWorktime(HttpServletResponse response, Long projectId, String month) {
        validateScopeSingleProject(projectId, month);
        // scoped 闸门（无权即抛 ServiceException，由全局异常处理转 500）
        Project project = projectService.selectProjectById(projectId);

        // researcher 角色仅本人行（与 /biz/rd/worktime/monthly/list researcher 通道口径一致；
        // 此前 selectMembersByProjectMonth 未做此过滤，导致 researcher 看见全组成员工时 — fix I4）
        Long selfUserId = isResearcherOnly() ? SecurityUtils.getUserId() : null;
        List<RdWorktimeMonthly> rows = rdWorktimeMonthlyMapper.selectMembersByProjectMonthForExport(
                projectId, month, selfUserId);

        Workbook wb = new XSSFWorkbook();
        try {
            Map<String, CellStyle> styles = createStyles(wb);
            Sheet sheet = wb.createSheet("工时统计表");
            sheet.setDefaultColumnWidth(18);

            // 表头
            String[] headers = {"研发人员", "月工时", "累计工时"};
            Row headRow = sheet.createRow(0);
            headRow.setHeightInPoints(22);
            for (int i = 0; i < headers.length; i++) {
                Cell c = headRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(styles.get("header"));
            }

            // 数据行
            int r = 1;
            for (RdWorktimeMonthly m : rows) {
                Row row = sheet.createRow(r++);
                writeString(row, 0, m.getResearcherName(), styles.get("text"));
                writeDecimal(row, 1, m.getTotalRdHours(), styles.get("amount"));
                writeDecimal(row, 2, m.getCumulativeHours(), styles.get("amount"));
            }

            // 文件名：课题编号_工时统计_YYYY-MM.xlsx
            String filename = "工时统计_" + safeFileSegment(project.getProjectNo()) + "_" + month + ".xlsx";
            writeResponse(response, wb, filename);
        } finally {
            closeQuietly(wb);
        }
    }

    // ========================================================
    //  端点 17 — 某课题某月工资及附加费统计表（动态列 + 合计行）
    // ========================================================

    @Override
    public void exportAllocation(HttpServletResponse response, Long projectId, String month) {
        validateScopeSingleProject(projectId, month);
        Project project = projectService.selectProjectById(projectId);

        // 批次行
        List<RdLaborAllocation> rows = rdLaborAllocationMapper.selectByProjectAndMonth(projectId, month);
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException("该月无分摊批次，请先计算");
        }

        // researcher 对他人行 hourlyRate 置 null（任务卡 D11）
        boolean maskHourly = isResearcherOnly();
        Long me = maskHourly ? SecurityUtils.getUserId() : null;

        // 附加费 ACTIVE 列表（按 rate_id 升序固定）
        List<SurchargeRate> rates = surchargeRateMapper.selectActiveRates();
        if (rates == null || rates.isEmpty()) {
            throw new ServiceException("附加费比例未配置");
        }

        Workbook wb = new XSSFWorkbook();
        try {
            Map<String, CellStyle> styles = createStyles(wb);
            Sheet sheet = wb.createSheet("工资及附加费统计表");
            sheet.setDefaultColumnWidth(16);

            // 表头：研发人员 | 月工时 | 时薪 | 分摊人工费 | 10 项附加费 | 附加费合计 | 总计
            int headerCols = 4 + rates.size() + 2;
            Row headRow = sheet.createRow(0);
            headRow.setHeightInPoints(22);
            String[] baseHeaders = {"研发人员", "月工时", "时薪", "分摊人工费"};
            for (int i = 0; i < baseHeaders.length; i++) {
                Cell c = headRow.createCell(i);
                c.setCellValue(baseHeaders[i]);
                c.setCellStyle(styles.get("header"));
            }
            for (int i = 0; i < rates.size(); i++) {
                Cell c = headRow.createCell(baseHeaders.length + i);
                c.setCellValue(rates.get(i).getRateName());
                c.setCellStyle(styles.get("header"));
            }
            Cell cSurchargeTotal = headRow.createCell(baseHeaders.length + rates.size());
            cSurchargeTotal.setCellValue("附加费合计");
            cSurchargeTotal.setCellStyle(styles.get("header"));
            Cell cGrandTotal = headRow.createCell(baseHeaders.length + rates.size() + 1);
            cGrandTotal.setCellValue("总计");
            cGrandTotal.setCellStyle(styles.get("header"));

            // 数据行
            BigDecimal sumHours = BigDecimal.ZERO;
            BigDecimal sumAlloc = BigDecimal.ZERO;
            BigDecimal sumSurcharge = BigDecimal.ZERO;
            BigDecimal sumGrand = BigDecimal.ZERO;
            BigDecimal[] sumSurchargeByRate = new BigDecimal[rates.size()];

            int r = 1;
            for (RdLaborAllocation a : rows) {
                Row row = sheet.createRow(r++);
                // 0 研发人员
                writeString(row, 0, a.getResearcherName(), styles.get("text"));
                // 1 月工时
                BigDecimal hours = nz(a.getMonthlyHours());
                writeDecimal(row, 1, hours, styles.get("amount"));
                sumHours = sumHours.add(hours);
                // 2 时薪（脱敏）
                if (maskHourly && a.getResearcherId() != null && !a.getResearcherId().equals(me)) {
                    writeString(row, 2, "", styles.get("text"));
                } else {
                    writeDecimal(row, 2, a.getHourlyRate(), styles.get("amount"));
                }
                // 3 分摊人工费
                BigDecimal alloc = nz(a.getAllocatedAmount());
                writeDecimal(row, 3, alloc, styles.get("amount"));
                sumAlloc = sumAlloc.add(alloc);

                // 4..4+rates.size()-1 各项附加费（按 rate_code 从 JSON 取；缺键补 0.00）
                // 分列合计（10 项附加费列的合计行）走 JSON 求和 — 列内值仅 JSON 持有
                Map<String, String> detailMap = parseSurchargeDetail(a.getSurchargeDetail());
                BigDecimal rowSurcharge = BigDecimal.ZERO;
                for (int k = 0; k < rates.size(); k++) {
                    String code = rates.get(k).getRateCode();
                    String raw = detailMap.get(code);
                    BigDecimal val = BigDecimal.ZERO;
                    if (raw != null && !raw.isEmpty()) {
                        try {
                            val = new BigDecimal(raw).setScale(SCALE, ROUNDING);
                        } catch (NumberFormatException ignored) {
                            // 非法值按 0 处理
                        }
                    }
                    writeDecimal(row, baseHeaders.length + k, val, styles.get("amount"));
                    rowSurcharge = rowSurcharge.add(val);
                    sumSurchargeByRate[k] = (sumSurchargeByRate[k] == null ? BigDecimal.ZERO : sumSurchargeByRate[k]).add(val);
                }
                // 附加费合计 / 总计（合计行的"附加费合计"列以 DB 落库的 surchargeTotal 为准 — 与总计列同源；
                // JSON 求和仅用于 10 项分列。fix I2）
                BigDecimal surchargeTotal = nz(a.getSurchargeTotal());
                BigDecimal grandTotal = nz(a.getGrandTotal());
                writeDecimal(row, baseHeaders.length + rates.size(), surchargeTotal, styles.get("amount"));
                writeDecimal(row, baseHeaders.length + rates.size() + 1, grandTotal, styles.get("amount"));
                sumSurcharge = sumSurcharge.add(surchargeTotal);
                sumGrand = sumGrand.add(grandTotal);
            }

            // 合计行（最后一行）
            Row totalRow = sheet.createRow(r);
            Cell totalLabel = totalRow.createCell(0);
            totalLabel.setCellValue("合计");
            totalLabel.setCellStyle(styles.get("total"));
            // 1 月工时合计
            writeDecimal(totalRow, 1, sumHours.setScale(SCALE, ROUNDING), styles.get("totalAmount"));
            // 2 时薪合计（无意义，留空）
            Cell emptyHourly = totalRow.createCell(2);
            emptyHourly.setCellStyle(styles.get("totalText"));
            // 3 分摊人工费合计
            writeDecimal(totalRow, 3, sumAlloc.setScale(SCALE, ROUNDING), styles.get("totalAmount"));
            // 4..4+rates.size()-1 各项附加费合计
            for (int k = 0; k < rates.size(); k++) {
                BigDecimal colSum = (sumSurchargeByRate[k] == null ? BigDecimal.ZERO : sumSurchargeByRate[k]).setScale(SCALE, ROUNDING);
                writeDecimal(totalRow, baseHeaders.length + k, colSum, styles.get("totalAmount"));
            }
            // 附加费合计 / 总计
            writeDecimal(totalRow, baseHeaders.length + rates.size(), sumSurcharge.setScale(SCALE, ROUNDING), styles.get("totalAmount"));
            writeDecimal(totalRow, baseHeaders.length + rates.size() + 1, sumGrand.setScale(SCALE, ROUNDING), styles.get("totalAmount"));

            String filename = "工资及附加费统计_" + safeFileSegment(project.getProjectNo()) + "_" + month + ".xlsx";
            writeResponse(response, wb, filename);
        } finally {
            closeQuietly(wb);
        }
    }

    // ========================================================
    //  端点 18 — 多课题某年汇总（聚合 + 总计行）
    // ========================================================

    @Override
    public void exportSummary(HttpServletResponse response, Integer year, List<Long> projectIds) {
        if (year == null || year < 1900 || year > 9999) {
            throw new ServiceException("year 非法");
        }
        // 解析 projectIds：null/空 → 全部可见课题
        List<Long> effectiveIds;
        if (projectIds == null || projectIds.isEmpty()) {
            List<Project> all = projectService.selectProjectList(new Project());
            if (all == null || all.isEmpty()) {
                throw new ServiceException("无可见课题");
            }
            effectiveIds = new ArrayList<>(all.size());
            for (Project p : all) {
                effectiveIds.add(p.getProjectId());
            }
        } else {
            effectiveIds = new ArrayList<>(projectIds);
        }
        // 逐个过 scoped 闸门
        Map<Long, Project> projectMap = new LinkedHashMap<>(effectiveIds.size());
        for (Long pid : effectiveIds) {
            Project p = projectService.selectProjectById(pid);
            projectMap.put(pid, p);
        }

        List<RdAllocSummaryRow> rows = rdLaborAllocationMapper.aggregateByProjectsYear(effectiveIds, year);

        Workbook wb = new XSSFWorkbook();
        try {
            Map<String, CellStyle> styles = createStyles(wb);
            Sheet sheet = wb.createSheet(year + "年多课题汇总");
            sheet.setDefaultColumnWidth(18);

            // 表头：课题编号 | 课题名称 | 月份 | Σalloc | Σsurcharge | Σgrand
            String[] headers = {"课题编号", "课题名称", "月份", "Σ人工费", "Σ附加费", "Σ总计"};
            Row headRow = sheet.createRow(0);
            headRow.setHeightInPoints(22);
            for (int i = 0; i < headers.length; i++) {
                Cell c = headRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(styles.get("header"));
            }

            BigDecimal sumAlloc = BigDecimal.ZERO;
            BigDecimal sumSurcharge = BigDecimal.ZERO;
            BigDecimal sumGrand = BigDecimal.ZERO;
            int r = 1;
            for (RdAllocSummaryRow row : rows) {
                Project p = projectMap.get(row.getProjectId());
                Row excelRow = sheet.createRow(r);
                writeString(excelRow, 0, p == null ? "" : p.getProjectNo(), styles.get("text"));
                writeString(excelRow, 1, p == null ? "" : p.getProjectName(), styles.get("text"));
                writeString(excelRow, 2, row.getMonth(), styles.get("text"));
                BigDecimal sa = nz(row.getSumAlloc());
                BigDecimal ss = nz(row.getSumSurcharge());
                BigDecimal sg = nz(row.getSumGrand());
                writeDecimal(excelRow, 3, sa, styles.get("amount"));
                writeDecimal(excelRow, 4, ss, styles.get("amount"));
                writeDecimal(excelRow, 5, sg, styles.get("amount"));
                sumAlloc = sumAlloc.add(sa);
                sumSurcharge = sumSurcharge.add(ss);
                sumGrand = sumGrand.add(sg);
                r++;
            }

            // 总计行
            Row totalRow = sheet.createRow(r);
            Cell label = totalRow.createCell(0);
            label.setCellValue("总计");
            label.setCellStyle(styles.get("total"));
            // 课题名称 / 月份 留空（仅样式）
            Cell c1 = totalRow.createCell(1);
            c1.setCellStyle(styles.get("totalText"));
            Cell c2 = totalRow.createCell(2);
            c2.setCellStyle(styles.get("totalText"));
            writeDecimal(totalRow, 3, sumAlloc.setScale(SCALE, ROUNDING), styles.get("totalAmount"));
            writeDecimal(totalRow, 4, sumSurcharge.setScale(SCALE, ROUNDING), styles.get("totalAmount"));
            writeDecimal(totalRow, 5, sumGrand.setScale(SCALE, ROUNDING), styles.get("totalAmount"));

            String filename = year + "年多课题分摊汇总.xlsx";
            writeResponse(response, wb, filename);
        } finally {
            closeQuietly(wb);
        }
    }

    // ========================================================
    //  私有工具
    // ========================================================

    /** 校验 projectId + month 基础合法性 */
    private void validateScopeSingleProject(Long projectId, String month) {
        if (projectId == null) {
            throw new ServiceException("projectId 必填");
        }
        if (StringUtils.isEmpty(month)) {
            throw new ServiceException("month 必填");
        }
        if (!MONTH_PATTERN.matcher(month).matches()) {
            throw new ServiceException("month 格式必须为 YYYY-MM");
        }
    }

    /**
     * 当前登录用户是否「精确」为 researcher（不含 admin） — 复用 Task 2/3 已有写法。
     */
    private boolean isResearcherOnly() {
        try {
            List<SysRole> roles = SecurityUtils.getLoginUser().getUser().getRoles();
            if (roles == null || roles.isEmpty()) {
                return false;
            }
            boolean hasAdmin = false;
            boolean hasResearcher = false;
            for (SysRole r : roles) {
                if (r == null || StringUtils.isEmpty(r.getRoleKey())) {
                    continue;
                }
                if (ROLE_ADMIN.equals(r.getRoleKey())) {
                    hasAdmin = true;
                }
                if (ROLE_RESEARCHER.equals(r.getRoleKey())) {
                    hasResearcher = true;
                }
            }
            return hasResearcher && !hasAdmin;
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析 surcharge_detail JSON；解析失败返回空 Map（行所有附加费按 0 处理） */
    private Map<String, String> parseSurchargeDetail(String json) {
        if (StringUtils.isEmpty(json)) {
            return Collections.emptyMap();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 文件名安全化（去特殊字符，避免浏览器下载乱码/路径穿越） */
    private static String safeFileSegment(String s) {
        if (s == null || s.isEmpty()) {
            return "unknown";
        }
        return s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    private static void writeString(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void writeDecimal(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    /** 表头/数据/合计样式 */
    private static Map<String, CellStyle> createStyles(Workbook wb) {
        Map<String, CellStyle> map = new LinkedHashMap<>();
        // header
        CellStyle header = wb.createCellStyle();
        header.setAlignment(HorizontalAlignment.CENTER);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        Font hf = wb.createFont();
        hf.setFontName("Microsoft YaHei");
        hf.setFontHeightInPoints((short) 11);
        hf.setBold(true);
        hf.setColor(IndexedColors.WHITE.getIndex());
        header.setFont(hf);
        header.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        applyBorders(header);
        map.put("header", header);

        // text
        CellStyle text = wb.createCellStyle();
        text.setAlignment(HorizontalAlignment.LEFT);
        text.setVerticalAlignment(VerticalAlignment.CENTER);
        Font tf = wb.createFont();
        tf.setFontName("Microsoft YaHei");
        tf.setFontHeightInPoints((short) 10);
        text.setFont(tf);
        applyBorders(text);
        map.put("text", text);

        // amount
        CellStyle amount = wb.createCellStyle();
        amount.setAlignment(HorizontalAlignment.RIGHT);
        amount.setVerticalAlignment(VerticalAlignment.CENTER);
        amount.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat(AMOUNT_FORMAT));
        Font af = wb.createFont();
        af.setFontName("Microsoft YaHei");
        af.setFontHeightInPoints((short) 10);
        amount.setFont(af);
        applyBorders(amount);
        map.put("amount", amount);

        // total（合计行文本单元格）
        CellStyle total = wb.createCellStyle();
        total.setAlignment(HorizontalAlignment.CENTER);
        total.setVerticalAlignment(VerticalAlignment.CENTER);
        Font totalF = wb.createFont();
        totalF.setFontName("Microsoft YaHei");
        totalF.setFontHeightInPoints((short) 10);
        totalF.setBold(true);
        total.setFont(totalF);
        applyBorders(total);
        total.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
        total.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        map.put("total", total);

        // totalText（合计行空文本单元格）
        CellStyle totalText = wb.createCellStyle();
        totalText.cloneStyleFrom(total);
        totalText.setAlignment(HorizontalAlignment.LEFT);
        map.put("totalText", totalText);

        // totalAmount（合计行数值单元格）
        CellStyle totalAmount = wb.createCellStyle();
        totalAmount.cloneStyleFrom(total);
        totalAmount.setAlignment(HorizontalAlignment.RIGHT);
        totalAmount.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat(AMOUNT_FORMAT));
        map.put("totalAmount", totalAmount);

        return map;
    }

    private static void applyBorders(CellStyle style) {
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
    }

    private static void writeResponse(HttpServletResponse response, Workbook wb, String filename) {
        try {
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("utf-8");
            String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
            response.setHeader("Content-disposition", "attachment;filename=" + encoded);
            wb.write(response.getOutputStream());
            response.getOutputStream().flush();
        } catch (IOException e) {
            throw new ServiceException("导出 Excel 失败：" + e.getMessage());
        }
    }

    private static void closeQuietly(Workbook wb) {
        try {
            if (wb != null) {
                wb.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
    }
}