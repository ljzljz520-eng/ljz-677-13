package com.excel.dto.mock;

import lombok.Data;

import java.util.List;

/**
 * 国家平台响应报文
 */
@Data
public class NationalReportResponse {

    /** 200-整批受理；400-部分/整批校验失败；500-平台异常 */
    private Integer code;

    private String message;

    /** 平台追踪流水号 */
    private String traceId;

    private List<ItemResult> results;

    @Data
    public static class ItemResult {
        private Long id;
        private String dataCode;
        /** true-成功 false-失败 */
        private Boolean success;
        private String errorMsg;
    }
}
