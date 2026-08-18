package com.ruoyi.biz.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 节假日配置（biz_holiday，V1.0.19）
 *
 * <p>周末默认休息不入表；HOLIDAY 行把工作日覆盖为休（法定假日），
 * WORKDAY 行把周末覆盖为班（调休补班）。每年国务院公布次年安排后 SQL 补数据。</p>
 *
 * @author kys
 * @date 2026-08-17
 */
@Data
@TableName("biz_holiday")
public class BizHoliday {

    /** 类型：法定放假日 */
    public static final String TYPE_HOLIDAY = "HOLIDAY";

    /** 类型：调休上班日（覆盖周末为工作日） */
    public static final String TYPE_WORKDAY = "WORKDAY";

    /** 日期（主键） */
    @TableId(value = "holiday_date", type = IdType.INPUT)
    private Date holidayDate;

    /** 类型：HOLIDAY 放假 / WORKDAY 调休上班 */
    private String holidayType;

    /** 节日名 */
    private String holidayName;
}
