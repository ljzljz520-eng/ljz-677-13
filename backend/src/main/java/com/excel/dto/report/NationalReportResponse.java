package com.excel.dto.report;

import lombok.Data;

import java.util.List;

/**
 * 国家平台返回报文
 */
@Data
public class NationalReportResponse {

    /**
     * 平台接收是否成功（整批）
     */
    private Boolean success;

    /**
     * 平台回执编码
     */
    private String code;

    /**
     * 平台回执消息
     */
    private String message;

    /**
     * 平台接收批次号/回执号
     */
    private String receiptNo;

    /**
     * 明细处理结果
     */
    private List<ItemResult> results;

    @Data
    public static class ItemResult {
        private Long id;
        private String dataCode;
        private Boolean success;
        private String message;

        public static ItemResult of(Long id, String dataCode, boolean success, String message) {
            ItemResult r = new ItemResult();
            r.id = id;
            r.dataCode = dataCode;
            r.success = success;
            r.message = message;
            return r;
        }
    }
}
