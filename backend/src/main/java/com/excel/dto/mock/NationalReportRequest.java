package com.excel.dto.mock;

import lombok.Data;

import java.util.List;

/**
 * 上送国家平台请求报文
 */
@Data
public class NationalReportRequest {

    /** 上送任务编号 */
    private String taskNo;

    /** 导入批次号 */
    private String batchNo;

    /** 批次序号，从1开始 */
    private Integer batchIndex;

    /** 本批条数 */
    private Integer totalCount;

    /** 上送时间戳 */
    private Long timestamp;

    /** 数据明细 */
    private List<Item> items;

    @Data
    public static class Item {
        private Long id;
        private String dataCode;
        private String name;
        private String idCard;
        private String phone;
        private String amount;
        private String address;
    }
}
