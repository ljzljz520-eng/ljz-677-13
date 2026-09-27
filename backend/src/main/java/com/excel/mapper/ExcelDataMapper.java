package com.excel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.excel.entity.ExcelData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExcelDataMapper extends BaseMapper<ExcelData> {

    @Select("SELECT * FROM excel_data WHERE batch_no = #{batchNo} AND report_status = #{status} AND deleted = 0")
    List<ExcelData> selectByBatchAndStatus(@Param("batchNo") String batchNo, @Param("status") Integer status);

    @Select("SELECT COUNT(*) FROM excel_data WHERE batch_no = #{batchNo} AND deleted = 0")
    Integer countByBatch(@Param("batchNo") String batchNo);

    @org.apache.ibatis.annotations.Update(
            "UPDATE excel_data SET report_status = 0, report_message = NULL, report_time = NULL " +
            "WHERE batch_no = #{batchNo} AND report_status = 2 AND deleted = 0")
    int clearFailedByBatch(@Param("batchNo") String batchNo);

    @org.apache.ibatis.annotations.Update(
            "<script>UPDATE excel_data SET report_status = 3 WHERE deleted = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int markSendingByIds(@Param("ids") java.util.Collection<Long> ids);
}
