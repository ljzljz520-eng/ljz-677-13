package com.excel.dto.report;

import lombok.Data;

import java.util.List;

/**
 * 上报国家平台的请求报文（每批一个清单）
 */
@Data
public class NationalReportRequest {

    /**
     * 上送任务编号
     */
    private String jobNo;

    /**
     * 导入批次号
     */
    private String batchNo;

    /**
     * 批次序号
     */
    private Integer seqNo;

    /**
     * 本批清单
     */
    private List<NationalReportItem> items;

    @Data
    public static class NationalReportItem {
        private Long id;
        private String dataCode;
        private String name;
        private String idCard;
        private String phone;
        private String amount;
        private String address;
        private String remark;
    }
}
