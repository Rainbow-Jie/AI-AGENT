package com.goocidata.agent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 系统设置 Mapper
 *
 * @author nzj
 */
@Mapper
public interface SysSettingMapper {

    @Select("SELECT k, v FROM sys_setting")
    List<Map<String, Object>> listAll();

    @Select("SELECT v FROM sys_setting WHERE k = #{k}")
    String get(@Param("k") String k);

    @Insert("INSERT INTO sys_setting(k, v) VALUES(#{k}, #{v}) "
            + "ON CONFLICT(k) DO UPDATE SET v = excluded.v")
    int upsert(@Param("k") String k, @Param("v") String v);

    @org.apache.ibatis.annotations.Delete("DELETE FROM sys_setting WHERE k = #{k}")
    int delete(@Param("k") String k);
}
