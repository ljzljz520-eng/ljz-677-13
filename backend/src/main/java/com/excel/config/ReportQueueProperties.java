package com.excel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上送队列配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "report.queue")
public class ReportQueueProperties {

    /** 每批上送条数 */
    private int batchSize = 100;

    /** 某批失败后是否继续后续批次：false-暂停，true-继续 */
    private boolean continueOnFail = false;

    /** 批次间隔（毫秒） */
    private long intervalMs = 500;

    /** 国家平台接口地址 */
    private String platformUrl;

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 5000;

    /** 读取超时（毫秒） */
    private int readTimeoutMs = 15000;

    /** 模拟：单条失败率 */
    private double itemFailRate = 0.05;

    /** 模拟：整批拒收率 */
    private double batchFailRate = 0.10;

    /** 模拟：系统异常率 */
    private double batchErrorRate = 0.03;

    /** 模拟：平台处理最小耗时 */
    private long latencyMinMs = 200;

    /** 模拟：平台处理最大耗时 */
    private long latencyMaxMs = 800;
}
